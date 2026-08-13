package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.ClothingAnalysis
import com.orbit.ai.RecommendCandidate
import com.orbit.ai.RecommendRequest
import com.orbit.ai.gemini.GeminiClient
import com.orbit.ai.gemini.GeminiKeyStore
import com.orbit.ai.gemini.GeminiOutfitRecommender
import com.orbit.ai.gemini.GeminiProperties
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 옷의 속성축 — subCategory · material · fit · season.
 *
 * 이름과 색만 저장하던 동안 추천은 "얇은 리넨"과 "두꺼운 니트"를 구분할 근거가
 * 없었다. 축을 나눠 저장하는 것이 이 기능이고, 확인해야 할 것은 셋이다.
 *  1) 등록·수정·분석 세 경로가 **같은 필드 집합**을 다루는가 (한 곳만 빠져도 읽기 전용이 된다)
 *  2) 저장한 값이 **추천 프롬프트까지 도달**하는가 (저장만 되면 아무것도 나아지지 않는다)
 *  3) 값이 없을 때 **프롬프트에서 사라지는가** (자리 채우기는 비용이고, 모델에게는 추측 신호다)
 */

/** 프롬프트 문자열 검증. 네트워크를 타지 않는다 — `buildPrompt` 가 `internal` 이다. */
@DisplayName("옷 속성축 — 추천 프롬프트 반영")
class ClothesAttributesPromptTest {

    private val properties = GeminiProperties(apiKey = "")
    private val recommender =
        GeminiOutfitRecommender(GeminiClient(properties, GeminiKeyStore()), properties, ObjectMapper())

    private fun promptOf(vararg candidates: RecommendCandidate) =
        recommender.buildPrompt(RecommendRequest(candidates = candidates.toList()))

    @Test
    fun `속성이 전부 있으면 한 줄에 모두 들어간다`() {
        val prompt = promptOf(
            RecommendCandidate(
                id = 1L,
                name = "흰 셔츠",
                mainCategory = MainCategory.TOP,
                color = "화이트",
                detail = "가슴에 자수",
                subCategory = "셔츠",
                material = "린넨",
                fit = "레귤러",
                season = "여름",
            ),
        )

        val line = prompt.lines().first { "id=1" in it }
        assertTrue("종류:셔츠" in line, "줄: $line")
        assertTrue("색:화이트" in line, "줄: $line")
        assertTrue("소재:린넨" in line, "줄: $line")
        assertTrue("핏:레귤러" in line, "줄: $line")
        assertTrue("계절:여름" in line, "줄: $line")
        assertTrue("가슴에 자수" in line, "줄: $line")
    }

    /**
     * 옷 한 벌이 여러 줄을 차지하면 옷장 100벌이 그대로 호출당 비용이 된다.
     * 목록은 매 추천마다 통째로 실려 나간다.
     */
    @Test
    fun `속성이 많아도 옷 한 벌은 한 줄이다`() {
        val prompt = promptOf(
            RecommendCandidate(
                1L, "흰 셔츠", MainCategory.TOP, "화이트", "가슴에 자수",
                subCategory = "셔츠", material = "린넨", fit = "레귤러", season = "여름",
            ),
        )

        assertEquals(1, prompt.lines().count { "id=1" in it })
    }

    /**
     * 이 기능에서 가장 중요한 한 가지. 빈 속성을 "소재:없음"처럼 채우면 아무 정보도
     * 주지 않으면서 토큰만 늘고, 모델에게는 "여기 뭔가 있어야 한다"는 신호로 읽혀
     * 없는 정보를 추측하게 만든다.
     */
    @Test
    fun `값이 없는 속성은 줄에서 아예 빠진다`() {
        val prompt = promptOf(
            RecommendCandidate(2L, "청바지", MainCategory.BOTTOM, null, null, material = "데님"),
        )

        val line = prompt.lines().first { "id=2" in it }
        assertTrue("소재:데님" in line, "있는 값은 들어가야 한다: $line")
        assertFalse("종류:" in line, "빈 속성의 라벨이 남았다: $line")
        assertFalse("색:" in line, "빈 속성의 라벨이 남았다: $line")
        assertFalse("핏:" in line, "빈 속성의 라벨이 남았다: $line")
        assertFalse("계절:" in line, "빈 속성의 라벨이 남았다: $line")
        assertFalse("null" in line, "null 이 문자열로 새어 나갔다: $line")
    }

    @Test
    fun `공백만 있는 속성도 없는 것으로 본다`() {
        val prompt = promptOf(
            RecommendCandidate(3L, "코트", MainCategory.OUTER, "  ", "  ", material = "  ", fit = "  "),
        )

        val line = prompt.lines().first { "id=3" in it }
        assertEquals("  - id=3 / 코트", line)
    }

    /** 속성이 하나도 없는 옷장(=마이그레이션 직후)도 프롬프트가 성립해야 한다. */
    @Test
    fun `속성이 하나도 없어도 프롬프트는 성립한다`() {
        val prompt = promptOf(
            RecommendCandidate(1L, "셔츠", MainCategory.TOP, null),
            RecommendCandidate(2L, "청바지", MainCategory.BOTTOM, null),
        )

        assertTrue("  - id=1 / 셔츠" in prompt)
        assertTrue("  - id=2 / 청바지" in prompt)
        assertTrue("id 를 새로 만들어내지 마라" in prompt, "기존 규칙은 그대로여야 한다")
    }

    /**
     * 값을 프롬프트에 넣기만 하고 규칙이 그것을 가리키지 않으면, 모델은 옷 이름만 보고
     * 계절감을 짐작한다. 그러면 속성을 뽑아 저장한 의미가 없다.
     */
    @Test
    fun `계절감과 핏 균형 규칙이 실제 속성 이름을 가리킨다`() {
        val prompt = promptOf(RecommendCandidate(1L, "셔츠", MainCategory.TOP, null))

        assertTrue("`계절`" in prompt, "계절 규칙이 속성을 가리켜야 한다")
        assertTrue("`핏`" in prompt, "핏 규칙이 속성을 가리켜야 한다")
        assertTrue("계절:여름" in prompt && "계절:겨울" in prompt, "어긋나는 조합의 예가 있어야 한다")
        assertTrue("추측해서 채우지 말고" in prompt, "빠진 속성을 지어내지 말라는 지시가 있어야 한다")
    }
}

/** 등록·수정·분석 세 경로가 같은 필드 집합을 다루는지. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfig::class)
@DisplayName("옷 속성축 — 등록·수정·분석 API")
class ClothesAttributesApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var analyzer: FakeClothingAnalyzer

    private lateinit var api: TestApiClient
    private lateinit var me: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        analyzer.reset()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("attrs@orbit.test")
    }

    private fun createJson(body: Map<String, Any?>) = mockMvc.perform(
        post("/api/clothes")
            .header(HttpHeaders.AUTHORIZATION, me.bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)),
    )

    private fun patchJson(id: Long, body: Map<String, Any?>) = mockMvc.perform(
        patch("/api/clothes/$id")
            .header(HttpHeaders.AUTHORIZATION, me.bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)),
    )

    private fun idOf(result: org.springframework.test.web.servlet.ResultActions): Long =
        api.json(result.andReturn().response.contentAsString)["id"].toString().toLong()

    @Test
    fun `등록에서 네 속성이 저장되고 응답에 그대로 나온다`() {
        createJson(
            mapOf(
                "name" to "린넨 셔츠",
                "mainCategory" to "TOP",
                "color" to "화이트",
                "subCategory" to "셔츠",
                "material" to "린넨",
                "fit" to "레귤러",
                "season" to "여름",
                "detail" to "가슴에 작은 자수",
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.subCategory").value("셔츠"))
            .andExpect(jsonPath("$.material").value("린넨"))
            .andExpect(jsonPath("$.fit").value("레귤러"))
            .andExpect(jsonPath("$.season").value("여름"))
            .andExpect(jsonPath("$.detail").value("가슴에 작은 자수"))
            .andExpect(jsonPath("$.color").value("화이트"))
    }

    /**
     * 마이그레이션 이전에 등록된 옷이 이 상태다. `ddl-auto: update` 로 컬럼이 붙으면
     * 기존 행은 전부 null 이고, **그게 정상이다.** 화면이 깨지지 않으려면 응답에
     * 키가 있고 값이 null 이어야 한다.
     */
    @Test
    fun `속성 없이 등록하면 전부 null 이고 조회도 멀쩡하다`() {
        val id = idOf(createJson(mapOf("name" to "옛날 셔츠", "mainCategory" to "TOP")).andExpect(status().isCreated))

        mockMvc.perform(get("/api/clothes/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("옛날 셔츠"))
            .andExpect(jsonPath("$.subCategory").doesNotExist())
            .andExpect(jsonPath("$.material").doesNotExist())
            .andExpect(jsonPath("$.fit").doesNotExist())
            .andExpect(jsonPath("$.season").doesNotExist())

        mockMvc.perform(get("/api/clothes").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].name").value("옛날 셔츠"))
    }

    @Test
    fun `사진과 함께 등록해도 네 속성이 저장된다`() {
        mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "shirt.png", "image/png", pngBytes()))
                .param("name", "니트")
                .param("mainCategory", "TOP")
                .param("subCategory", "니트")
                .param("material", "울 혼방")
                .param("fit", "오버핏")
                .param("season", "겨울")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.subCategory").value("니트"))
            .andExpect(jsonPath("$.material").value("울 혼방"))
            .andExpect(jsonPath("$.fit").value("오버핏"))
            .andExpect(jsonPath("$.season").value("겨울"))
            .andExpect(jsonPath("$.imageUrl").isString)
    }

    /** multipart 에는 `@Valid` 가 걸리지 않는다. 손으로 맞춘 검사가 실제로 도는지 본다. */
    @Test
    fun `multipart 에서도 길이 상한이 걸린다`() {
        mockMvc.perform(
            multipart("/api/clothes")
                .param("name", "니트")
                .param("mainCategory", "TOP")
                .param("fit", "핏".repeat(30)) // 상한 20
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `PATCH 로 네 속성을 고칠 수 있다`() {
        val id = idOf(
            createJson(
                mapOf(
                    "name" to "니트", "mainCategory" to "TOP",
                    "subCategory" to "니트", "material" to "울", "fit" to "슬림", "season" to "겨울",
                ),
            ).andExpect(status().isCreated),
        )

        patchJson(id, mapOf("material" to "울 혼방", "fit" to "오버핏"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.material").value("울 혼방"))
            .andExpect(jsonPath("$.fit").value("오버핏"))
            .andExpect(jsonPath("$.subCategory").value("니트")) // 안 보낸 필드는 유지
            .andExpect(jsonPath("$.season").value("겨울"))
    }

    /**
     * 대부분을 AI 가 채우는 값이라 **지우는 길**이 특히 중요하다. 지울 수 없으면
     * 잘못 들어간 값을 없애는 방법이 "옷을 지우고 다시 등록"뿐이 된다.
     * 규칙은 color·detail 과 같다 — null 은 그대로, 빈 문자열은 지우기.
     */
    @Test
    fun `빈 문자열을 보내면 속성이 지워진다`() {
        val id = idOf(
            createJson(
                mapOf(
                    "name" to "니트", "mainCategory" to "TOP",
                    "subCategory" to "니트", "material" to "울", "fit" to "슬림", "season" to "겨울",
                ),
            ).andExpect(status().isCreated),
        )

        patchJson(id, mapOf("material" to "", "season" to "   "))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.material").doesNotExist())
            .andExpect(jsonPath("$.season").doesNotExist())
            .andExpect(jsonPath("$.subCategory").value("니트"))
            .andExpect(jsonPath("$.fit").value("슬림"))

        val saved = requireNotNull(clothesRepository.findById(id).orElse(null))
        assertNull(saved.material)
        assertNull(saved.season)
    }

    @Test
    fun `PATCH 에서 보내지 않은 속성은 건드리지 않는다`() {
        val id = idOf(
            createJson(
                mapOf("name" to "니트", "mainCategory" to "TOP", "material" to "울", "season" to "겨울"),
            ).andExpect(status().isCreated),
        )

        patchJson(id, mapOf("name" to "두꺼운 니트")).andExpect(status().isOk)

        val saved = requireNotNull(clothesRepository.findById(id).orElse(null))
        assertEquals("두꺼운 니트", saved.name)
        assertEquals("울", saved.material)
        assertEquals("겨울", saved.season)
    }

    @Test
    fun `속성 길이 상한을 넘으면 400 이다`() {
        createJson(mapOf("name" to "셔츠", "mainCategory" to "TOP", "season" to "겨".repeat(21)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    /** 분석 응답은 등록 폼의 초기값이다. 여기서 빠진 필드는 화면이 채울 방법이 없다. */
    @Test
    fun `분석 응답에 네 속성이 실려 나온다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "shirt.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subCategory").value("셔츠"))
            .andExpect(jsonPath("$.material").value("린넨"))
            .andExpect(jsonPath("$.fit").value("레귤러"))
            .andExpect(jsonPath("$.season").value("여름"))
            .andExpect(jsonPath("$.name").value("흰 린넨 셔츠"))
    }

    @Test
    fun `AI 가 일부 속성을 못 뽑아도 나머지는 폼에 채워진다`() {
        analyzer.result = ClothingAnalysis(
            name = "청바지",
            mainCategory = MainCategory.BOTTOM,
            color = null,
            detail = null,
            material = "데님",
        )

        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "jeans.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.material").value("데님"))
            .andExpect(jsonPath("$.subCategory").doesNotExist())
            .andExpect(jsonPath("$.fit").doesNotExist())
            .andExpect(jsonPath("$.season").doesNotExist())
    }

    /** 코디 아이템 응답은 [ClothesResponse] 를 쓰지 않는다. 새 필드가 여기를 깨지 않는지 본다. */
    @Test
    fun `속성이 붙은 옷으로 만든 코디 응답은 그대로다`() {
        val top = idOf(
            createJson(
                mapOf("name" to "셔츠", "mainCategory" to "TOP", "material" to "면", "season" to "여름"),
            ).andExpect(status().isCreated),
        )
        val bottom = idOf(
            createJson(
                mapOf("name" to "슬랙스", "mainCategory" to "BOTTOM", "fit" to "와이드"),
            ).andExpect(status().isCreated),
        )

        mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("title" to "여름룩", "clothesIds" to listOf(top, bottom)))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].name").value("셔츠"))
            .andExpect(jsonPath("$.items[0].inWardrobe").value(true))
    }
}

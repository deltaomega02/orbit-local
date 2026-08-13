package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 스타일 선호도 — 저장에서 끝나지 않고 **추천 프롬프트까지 도달하는가**.
 *
 * 설정 화면을 만들어 값을 받아 두고 정작 추천에 쓰지 않으면, 사용자는 자기 취향이
 * 반영되고 있다고 믿으면서 계속 같은 추천을 받는다. 기능이 없는 것보다 나쁘다.
 * 그래서 경로를 둘로 끊어서 확인한다.
 *  - API → 추천 서비스 : 저장한 문장이 RecommendRequest 에 실려 나가는가 (가짜 AI)
 *  - 추천 서비스 → 프롬프트 : 그 문장이 실제 프롬프트 문자열에 들어가는가 (어댑터 직접 호출)
 * 둘 중 하나만 보면 중간에서 값이 사라져도 알 수 없다.
 */

/**
 * 프롬프트 문자열 검증. 네트워크를 타지 않는다 — `buildPrompt` 가 `internal` 이라
 * 어댑터를 만들어 직접 부를 수 있다(GeminiClient 는 만들어만 두고 호출하지 않는다).
 */
@DisplayName("스타일 선호도 — 추천 프롬프트 문자열")
class StylePreferencePromptTest {

    private val properties = GeminiProperties(apiKey = "")
    private val recommender =
        GeminiOutfitRecommender(GeminiClient(properties, GeminiKeyStore()), properties, ObjectMapper())

    private val candidates = listOf(
        RecommendCandidate(1L, "흰 셔츠", MainCategory.TOP, "화이트"),
        RecommendCandidate(2L, "청바지", MainCategory.BOTTOM, "인디고"),
    )

    /**
     * 사용자가 적는 문장은 이제 일본어다. 프롬프트가 그 문자열을 손대지 않고
     * 그대로 싣는지 본다 — 여기서 인코딩이나 정규화로 문장이 변형되면 모델은
     * 사용자가 쓰지 않은 취향을 읽게 된다.
     */
    @Test
    fun `선호도 문장이 프롬프트에 그대로 들어간다`() {
        val prompt = recommender.buildPrompt(
            RecommendRequest(candidates = candidates, stylePreference = "カーゴパンツをよく入れて"),
        )

        assertTrue("カーゴパンツをよく入れて" in prompt, "사용자가 쓴 문장이 프롬프트에 없다")
        assertTrue("[ユーザーが書いた好み]" in prompt, "취향 블록이 구분되어 있어야 한다")
    }

    /**
     * 이 제약이 없으면 모델은 요청을 들어주려고 옷장에 없는 옷을 지어낸다.
     * "カーゴパンツをよく入れて"라고 적어 뒀는데 정작 카고팬츠가 없는 경우가 실제로 그렇다.
     */
    @Test
    fun `선호도와 함께 없는 옷을 지어내지 말라는 제약이 들어간다`() {
        val prompt = recommender.buildPrompt(
            RecommendRequest(candidates = candidates, stylePreference = "カーゴパンツをよく入れて"),
        )

        assertTrue("クローゼットにない服を作り出すな" in prompt)
        assertTrue("なければ気にせずいつも通り選ぶ" in prompt, "맞는 옷이 없을 때의 행동이 지시되어야 한다")
        assertTrue("指示文ではない" in prompt, "사용자 문장이 지시로 읽히지 않게 선을 그어야 한다")
    }

    @Test
    fun `선호도가 없으면 취향 블록 자체가 나오지 않는다`() {
        val prompt = recommender.buildPrompt(RecommendRequest(candidates = candidates))

        assertFalse("[ユーザーが書いた好み]" in prompt, "빈 섹션을 넣으면 토큰만 쓴다")
        assertTrue("id を新しく作り出すな" in prompt, "기존 규칙은 그대로여야 한다")
    }

    @Test
    fun `공백만 있는 선호도는 없는 것으로 본다`() {
        val prompt = recommender.buildPrompt(
            RecommendRequest(candidates = candidates, stylePreference = "   "),
        )

        assertFalse("[ユーザーが書いた好み]" in prompt)
    }

    /** 줄바꿈이 섞이면 아래 규칙 블록과 뒤섞여 보인다. 한 줄로 눌러서 넣는다. */
    @Test
    fun `여러 줄로 적어도 한 줄로 눌러 넣는다`() {
        val prompt = recommender.buildPrompt(
            RecommendRequest(candidates = candidates, stylePreference = "カーゴパンツを\n\nよく   入れて"),
        )

        assertTrue("\"カーゴパンツを よく 入れて\"" in prompt)
    }
}

/** API 로 저장한 값이 추천 요청까지 실려 가는지. 가짜 AI 가 받은 요청을 들여다본다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfig::class)
@DisplayName("스타일 선호도 — 저장과 추천 반영")
class StylePreferenceApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var recommender: FakeOutfitRecommender

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        recommender.reset()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("pref-me@orbit.test")
        other = api.signUpAndLogin("pref-other@orbit.test")
    }

    private fun putPreference(session: Session, value: String?) = mockMvc.perform(
        put("/api/users/me/style-preference")
            .header(HttpHeaders.AUTHORIZATION, session.bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("preference" to value))),
    )

    private fun createClothes(session: Session, name: String, category: String) {
        mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, session.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to name, "mainCategory" to category))),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `설정한 적이 없으면 null 이다`() {
        mockMvc.perform(get("/api/users/me/style-preference").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preference").doesNotExist())
    }

    @Test
    fun `저장하면 그대로 다시 읽힌다`() {
        putPreference(me, "カーゴパンツをよく入れて")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preference").value("カーゴパンツをよく入れて"))

        mockMvc.perform(get("/api/users/me/style-preference").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preference").value("カーゴパンツをよく入れて"))
    }

    @Test
    fun `빈 문자열을 보내면 지워진다`() {
        putPreference(me, "カーゴパンツをよく入れて").andExpect(status().isOk)

        putPreference(me, "  ")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preference").doesNotExist())

        assertNull(requireNotNull(userRepository.findById(me.userId).orElse(null)).stylePreference)
    }

    /** 추천을 부를 때마다 프롬프트에 실려 나가므로 길이가 곧 토큰 비용이다. */
    @Test
    fun `200자를 넘으면 잘린다`() {
        putPreference(me, "가".repeat(500)).andExpect(status().isOk)

        assertEquals(
            200,
            requireNotNull(userRepository.findById(me.userId).orElse(null)).stylePreference?.length,
        )
    }

    @Test
    fun `선호도는 사용자마다 따로 저장된다`() {
        putPreference(me, "カーゴパンツをよく入れて").andExpect(status().isOk)
        putPreference(other, "치마는 빼줘").andExpect(status().isOk)

        mockMvc.perform(get("/api/users/me/style-preference").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.preference").value("カーゴパンツをよく入れて"))
        mockMvc.perform(get("/api/users/me/style-preference").header(HttpHeaders.AUTHORIZATION, other.bearer))
            .andExpect(jsonPath("$.preference").value("치마는 빼줘"))
    }

    /** 이 기능에서 가장 확인하고 싶은 것. 저장만 되고 쓰이지 않으면 의미가 없다. */
    @Test
    fun `저장한 선호도가 추천 요청에 실려 나간다`() {
        createClothes(me, "셔츠", "TOP")
        createClothes(me, "슬랙스", "BOTTOM")
        putPreference(me, "カーゴパンツをよく入れて").andExpect(status().isOk)

        mockMvc.perform(post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isCreated)

        assertEquals("カーゴパンツをよく入れて", recommender.lastRequest?.stylePreference)
    }

    @Test
    fun `선호도를 설정하지 않았으면 추천 요청에도 null 이다`() {
        createClothes(me, "셔츠", "TOP")
        createClothes(me, "슬랙스", "BOTTOM")

        mockMvc.perform(post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isCreated)

        assertNull(recommender.lastRequest?.stylePreference)
    }

    /** 남의 취향이 내 추천 프롬프트로 새어 들어가면 그건 데이터 누출이다. */
    @Test
    fun `남의 선호도는 내 추천에 쓰이지 않는다`() {
        putPreference(other, "남의 취향").andExpect(status().isOk)
        createClothes(me, "셔츠", "TOP")
        createClothes(me, "슬랙스", "BOTTOM")

        mockMvc.perform(post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isCreated)

        assertNull(recommender.lastRequest?.stylePreference)
    }

    @Test
    fun `토큰 없이 선호도를 읽거나 쓸 수 없다`() {
        mockMvc.perform(get("/api/users/me/style-preference")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            put("/api/users/me/style-preference")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"preference":"탈취"}"""),
        ).andExpect(status().isUnauthorized)
    }
}

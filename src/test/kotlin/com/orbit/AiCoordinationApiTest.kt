package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.OutfitSuggestion
import com.orbit.ai.TryOnImageGenerator
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI 추천·분석·가상 착용의 HTTP 계약.
 *
 * 실제 Gemini 는 여기 없다. [FakeAiConfig] 의 결정적인 가짜가 들어가므로 같은
 * 입력이면 언제나 같은 결과가 나오고, 네트워크가 개입할 여지도 없다.
 * 확인하려는 건 "AI 가 잘 동작할 때"가 아니라 **"AI 가 이상하게 굴 때 서버가
 * 무엇을 지키는가"** 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfig::class)
@DisplayName("AI — 추천·분석·가상 착용")
class AiCoordinationApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var analyzer: FakeClothingAnalyzer
    @Autowired lateinit var recommender: FakeOutfitRecommender
    @Autowired lateinit var tryOn: FakeTryOnImageGenerator

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        analyzer.reset()
        recommender.reset()
        tryOn.reset()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("ai-me@orbit.test")
        other = api.signUpAndLogin("ai-other@orbit.test")
    }

    private fun addClothes(session: Session, name: String, category: String, withImage: Boolean = true): Long {
        val request = multipart("/api/clothes")
        if (withImage) request.file(MockMultipartFile("image", "$name.png", "image/png", pngBytes()))
        request.param("name", name)
            .param("mainCategory", category)
            .header(HttpHeaders.AUTHORIZATION, session.bearer)

        val body = mockMvc.perform(request).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }

    private fun recommend(session: Session = me) =
        mockMvc.perform(post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, session.bearer))

    private fun uploadBodyPhoto(session: Session = me) = mockMvc.perform(
        multipart(HttpMethod.PUT, "/api/users/me/body-photo")
            .file(MockMultipartFile("image", "body.png", "image/png", pngBytes()))
            .header(HttpHeaders.AUTHORIZATION, session.bearer),
    ).andExpect(status().isOk)

    // ── 분석 ──────────────────────────────────────────────────────

    @Test
    fun `사진 분석은 제안만 하고 옷장에는 아무것도 저장하지 않는다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "shirt.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("흰 린넨 셔츠"))
            .andExpect(jsonPath("$.mainCategory").value("TOP"))
            .andExpect(jsonPath("$.color").value("화이트"))
            .andExpect(jsonPath("$.detail").isString)

        assertEquals(1, analyzer.calls)
        // 저장되지 않았다는 것이 이 엔드포인트의 계약이다
        assertEquals(0, clothesRepository.findAllByOwnerIdAndDeletedAtIsNull(me.userId).size)
        // 선언된 Content-Type 이 아니라 실제로 판별한 형식이 AI 로 넘어간다
        assertEquals("image/png", analyzer.lastMime)
    }

    @Test
    fun `분석도 이미지가 아니면 415 로 막는다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "x.png", "image/png", "그냥 텍스트".toByteArray()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isUnsupportedMediaType)

        assertEquals(0, analyzer.calls, "검증을 통과하지 못한 바이트가 AI 로 넘어가면 안 된다")
    }

    // ── 추천 ──────────────────────────────────────────────────────

    @Test
    fun `추천은 201 과 코디 · 추천 이유를 반환한다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")

        recommend()
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.title").value("오늘의 코디"))
            .andExpect(jsonPath("$.reason").value("무난하게 어울리는 조합입니다."))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].imageUrl").isString)
            // 추천 시점에는 가상 착용 이미지를 만들지 않는다. 느리고 돈이 드는 일을
            // 사용자가 요청하지도 않았는데 끼워 넣지 않기 위해서다.
            .andExpect(jsonPath("$.tryOnImageUrl").isEmpty)
    }

    @Test
    fun `상의만 있으면 추천을 400 으로 거절한다`() {
        addClothes(me, "셔츠", "TOP")

        recommend()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("not_enough_clothes"))

        assertEquals(0, recommender.calls, "조합이 불가능하면 AI 를 부르기 전에 끊는다")
    }

    /**
     * 이 프로젝트의 시그니처 계약. 추천 엔진은 확률적이라 같은 조합을 다시 내놓을 수
     * 있고, 프롬프트로 "피해라"라고 말하는 것만으로는 보장이 되지 않는다.
     * 서버가 당일 코디와 집합 비교로 판정하고 409 + retry 를 돌려준다.
     */
    @Test
    fun `AI 가 오늘 나온 조합을 다시 내놓으면 409 와 retry true 다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        recommend().andExpect(status().isCreated)

        recommender.behavior = { OutfitSuggestion("또 같은 코디", "같은 이유", listOf(top, bottom)) }

        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("duplicate"))
            .andExpect(jsonPath("$.retry").value(true))
            .andExpect(jsonPath("$.clothesIds.length()").value(2))
    }

    /** 3계층 방어의 첫 겹. 프롬프트에 오늘 조합이 실제로 실려 나가는지 확인한다. */
    @Test
    fun `추천 요청에는 오늘 이미 나온 조합이 회피 목록으로 전달된다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        addClothes(me, "청바지", "BOTTOM")

        recommend().andExpect(status().isCreated)
        recommend().andExpect(status().isCreated)

        assertTrue(setOf(top, bottom) in requireNotNull(recommender.lastRequest).avoidCombinations)
    }

    /**
     * 모델은 후보에 없던 id 를 태연히 지어낸다. 스키마로 "숫자 배열"이라는 모양은
     * 강제할 수 있어도 "실재하는 내 옷"은 강제할 수 없으므로, 서버가 소유 목록과
     * 대조한다. 이걸 빼면 AI 응답이 그대로 IDOR 통로가 된다.
     */
    @Test
    fun `AI 가 존재하지 않는 clothesId 를 반환하면 거부한다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        recommender.behavior = { OutfitSuggestion("유령 코디", "환각", listOf(999_999L)) }

        recommend()
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("ai_invalid_response"))

        assertEquals(0, coordinationRepository.count(), "거절된 추천은 저장되면 안 된다")
    }

    @Test
    fun `AI 가 남의 옷 id 를 반환해도 거부한다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        val othersBottom = addClothes(other, "남의 청바지", "BOTTOM")
        recommender.behavior = { req ->
            OutfitSuggestion("탈취 코디", "남의 옷", listOf(req.candidates.first().id, othersBottom))
        }

        recommend()
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("ai_invalid_response"))
    }

    @Test
    fun `추천 후보에는 내 옷만 들어간다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        addClothes(other, "남의 셔츠", "TOP")

        recommend().andExpect(status().isCreated)

        val names = requireNotNull(recommender.lastRequest).candidates.map { it.name }.toSet()
        assertEquals(setOf("셔츠", "슬랙스"), names, "남의 옷이 후보에 섞이면 그 자체로 정보 유출이다")
    }

    // ── 가상 착용 ─────────────────────────────────────────────────

    @Test
    fun `가상 착용은 한 번만 생성하고 두 번째부터는 같은 이미지를 그대로 준다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        uploadBodyPhoto()
        val body = recommend().andExpect(status().isCreated).andReturn().response.contentAsString
        val coordinationId = api.json(body)["id"].toString().toLong()

        val first = mockMvc.perform(
            post("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tryOnImageUrl").isString)
            .andReturn().response.contentAsString

        val second = mockMvc.perform(
            post("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(api.json(first)["tryOnImageUrl"], api.json(second)["tryOnImageUrl"])
        // "결과가 같다"가 아니라 "두 번째엔 생성기를 부르지 않았다"가 멱등성의 증거다
        assertEquals(1, tryOn.calls)
        assertEquals(2, tryOn.lastItemCount, "코디에 담긴 옷 사진이 함께 넘어가야 한다")

        // 생성된 이미지도 소유권 검사를 통과해야 조회된다
        val url = api.json(first)["tryOnImageUrl"] as String
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, me.bearer)).andExpect(status().isOk)
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, other.bearer)).andExpect(status().isNotFound)
    }

    @Test
    fun `전신 사진이 없으면 가상 착용은 400 이다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        val body = recommend().andExpect(status().isCreated).andReturn().response.contentAsString
        val coordinationId = api.json(body)["id"].toString().toLong()

        mockMvc.perform(
            post("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("no_body_photo"))

        assertEquals(0, tryOn.calls)
    }

    @Test
    fun `남의 코디에는 가상 착용을 걸 수 없다`() {
        addClothes(other, "셔츠", "TOP")
        addClothes(other, "슬랙스", "BOTTOM")
        val body = recommend(other).andExpect(status().isCreated).andReturn().response.contentAsString
        val othersCoordination = api.json(body)["id"].toString().toLong()
        uploadBodyPhoto(me)

        mockMvc.perform(
            post("/api/coordinations/$othersCoordination/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    // ── 구조 보장 ─────────────────────────────────────────────────

    /**
     * 테스트가 실제 네트워크를 타지 않는다는 것을 코드로 못박는다.
     * 설정(`orbit.gemini.enabled=false`)이 실수로 바뀌면 여기서 먼저 깨진다.
     */
    @Test
    fun `테스트 컨텍스트에는 실제 Gemini 구현이 존재하지 않는다`() {
        val registered = listOf(ClothingAnalyzer::class, OutfitRecommender::class, TryOnImageGenerator::class)
            .flatMap { context.getBeansOfType(it.java).values }

        assertEquals(3, registered.size, "가짜 3개만 있어야 한다")
        assertTrue(
            registered.none { it::class.java.name.startsWith("com.orbit.ai.gemini") },
            "테스트 컨텍스트에 실제 Gemini 어댑터가 등록됐다: ${registered.map { it::class.java.name }}",
        )
    }

    @Test
    fun `AI 엔드포인트도 토큰 없이는 전부 401`() {
        mockMvc.perform(post("/api/coordinations/recommend")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/coordinations/1/tryon")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "a.png", MediaType.IMAGE_PNG_VALUE, pngBytes())),
        ).andExpect(status().isUnauthorized)
    }
}

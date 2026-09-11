package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.OutfitSuggestion
import com.orbit.ai.TryOnImageGenerator
import com.orbit.media.MediaStorage
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 추천·분석·가상 착용의 HTTP 계약.
 * [FakeAiConfig] 의 결정적 가짜로 AI 가 이상하게 응답할 때 서버가 무엇을 지키는지 본다.
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
    @Autowired lateinit var mediaStorage: MediaStorage
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

    private fun deleteTryOn(coordinationId: Long, session: Session = me) = mockMvc.perform(
        delete("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, session.bearer),
    )

    /** 옷 두 벌 · 전신 사진 · 추천 · 가상 착용까지 끝낸 코디 하나. */
    private fun coordinationWithTryOn(): Long {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")
        uploadBodyPhoto()
        val body = recommend().andExpect(status().isCreated).andReturn().response.contentAsString
        val id = api.json(body)["id"].toString().toLong()
        mockMvc.perform(post("/api/coordinations/$id/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
        return id
    }

    private fun tryOnUrlOf(coordinationId: Long, session: Session = me): Any? {
        val body = mockMvc.perform(
            get("/api/coordinations/$coordinationId").header(HttpHeaders.AUTHORIZATION, session.bearer),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return api.json(body)["tryOnImageUrl"]
    }

    // --- 분석

    @Test
    fun `사진 분석은 제안만 하고 옷장에는 아무것도 저장하지 않는다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "shirt.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("白いリネンシャツ"))
            .andExpect(jsonPath("$.mainCategory").value("TOP"))
            .andExpect(jsonPath("$.color").value("ホワイト"))
            .andExpect(jsonPath("$.detail").isString)

        assertEquals(1, analyzer.calls)
        assertEquals(0, clothesRepository.findAllByOwnerIdAndDeletedAtIsNull(me.userId).size)
        // 선언된 Content-Type 이 아니라 실제로 판별한 형식이 AI 로 넘어간다
        assertEquals("image/png", analyzer.lastMime)
    }

    /**
     * AI 전송본은 base64 로 4/3 배 부풀어 요청 크기와 토큰 비용이 되므로 저장본보다 더 줄인다.
     * 응답만 보면 원본을 보내도 통과하므로 가짜가 받은 바이트를 직접 디코드한다.
     */
    @Test
    fun `분석에 넘기는 사진은 저장본보다 더 작게 줄여서 보낸다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "big.jpg", "image/jpeg", photoJpeg(4000, 3000)))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isOk)

        val sent = decodeImage(assertNotNull(analyzer.lastImage))
        assertEquals(768, sent.width, "AI 전송본의 긴 변은 768px 이다")
        assertEquals(576, sent.height, "비율이 유지되어야 한다")
        // 다시 인코딩했으므로 mime 도 실제 바이트와 맞아야 한다
        assertEquals("image/jpeg", analyzer.lastMime)
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

    // --- 추천

    @Test
    fun `추천은 201 과 코디 · 추천 이유를 반환한다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")

        recommend()
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.title").value("今日のコーデ"))
            .andExpect(jsonPath("$.reason").value("無理なくまとまる組み合わせです。"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].imageUrl").isString)
            // 느리고 비용이 드는 가상 착용은 추천 시점에 만들지 않는다
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
     * 프롬프트만으로는 중복을 막지 못해 서버가 당일 코디와 집합 비교로 판정한다.
     * 상의1·하의1 이면 AI 호출 전에 exhausted 로 끊기므로 하의를 2벌 둔다.
     */
    @Test
    fun `AI 가 오늘 나온 조합을 다시 내놓으면 409 와 retry true 다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        addClothes(me, "청바지", "BOTTOM") // 조합이 2가지라 하나를 써도 남는다
        recommend().andExpect(status().isCreated)

        recommender.behavior = { OutfitSuggestion("또 같은 코디", "같은 이유", listOf(top, bottom)) }

        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("duplicate"))
            .andExpect(jsonPath("$.retry").value(true))
            .andExpect(jsonPath("$.clothesIds.length()").value(2))

        assertEquals(2, recommender.calls, "남은 조합이 있으면 AI 를 부르는 것이 맞다")
    }

    /** 가능한 조합이 1개뿐이면 AI 호출과 duplicate 재시도가 낭비되므로 호출 전에 판정한다. */
    @Test
    fun `상의1 하의1 이면 두 번째 추천은 AI 를 부르지 않고 exhausted 다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")

        recommend().andExpect(status().isCreated)
        assertEquals(1, recommender.calls)

        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("exhausted"))
            // duplicate 와 달리 재시도해도 소용없다
            .andExpect(jsonPath("$.retry").value(false))
            .andExpect(jsonPath("$.detail").isString)

        assertEquals(1, recommender.calls, "가능한 조합이 없으면 AI 를 부르기 전에 끊는다")
    }

    /** 아우터가 한 벌이면 조합은 1×1×(1+1)=2 가지라 두 번째 추천은 exhausted 가 아니다. */
    @Test
    fun `아우터는 선택이라 조합 수를 두 배로 늘린다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        val outer = addClothes(me, "코트", "OUTER")

        // 1) 상의 + 하의
        recommend().andExpect(status().isCreated)

        // 2) 상의 + 하의 + 아우터
        recommender.behavior = { OutfitSuggestion("코트 코디", "쌀쌀해서", listOf(top, bottom, outer)) }
        recommend().andExpect(status().isCreated)
        assertEquals(2, recommender.calls)

        // 3) 두 조합을 모두 썼다
        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("exhausted"))
        assertEquals(2, recommender.calls)
    }

    /** 수동 코디는 추천 규칙을 따르지 않아(하의 없이도 생성 가능) 세면 남은 조합을 막게 된다. */
    @Test
    fun `추천이 만들 수 없는 모양의 수동 코디는 조합 수로 세지 않는다`() {
        val top = addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")

        mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("title" to "상의만", "clothesIds" to listOf(top)))),
        ).andExpect(status().isCreated)

        recommend().andExpect(status().isCreated)
        assertEquals(1, recommender.calls)
    }

    /** 중복 방어의 첫 단계로 오늘 조합을 프롬프트에 싣는다. */
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
     * 스키마로는 실재하는 내 옷 id 를 강제할 수 없어 서버가 소유 목록과 대조한다.
     * 빠지면 AI 응답이 IDOR 통로가 된다.
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

    // --- 가상 착용

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
        // 멱등성은 두 번째에 생성기를 부르지 않았는지로 확인한다
        assertEquals(1, tryOn.calls)
        assertEquals(2, tryOn.lastItemCount, "코디에 담긴 옷 사진이 함께 넘어가야 한다")

        // 생성된 이미지도 소유권 검사를 통과해야 조회된다
        val url = api.json(first)["tryOnImageUrl"] as String
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, me.bearer)).andExpect(status().isOk)
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, other.bearer)).andExpect(status().isNotFound)
    }

    /** 잘못 나온 이미지만 버리고 조합·추천 이유·LOOK 번호는 남길 수 있어야 한다. */
    @Test
    fun `가상 착용 이미지만 지우면 코디는 남고 tryOnImageUrl 이 null 로 돌아온다`() {
        val coordinationId = coordinationWithTryOn()
        val imageUrl = tryOnUrlOf(coordinationId) as String
        val storedPath = imageUrl.removePrefix("/media/")

        deleteTryOn(coordinationId).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/coordinations/$coordinationId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tryOnImageUrl").isEmpty)
            // 코디 자체는 남는다
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.lookNo").isNumber)

        // 경로만 지우면 고아 파일이 쌓인다
        assertNull(mediaStorage.read(storedPath), "가상 착용 이미지 파일이 그대로 남았다")
    }

    @Test
    fun `가상 착용 이미지가 없어도 삭제는 204 다`() {
        val coordinationId = coordinationWithTryOn()

        deleteTryOn(coordinationId).andExpect(status().isNoContent)
        // 두 번째도 204. 404 면 클라이언트가 지우기 전에 상태를 조회해야 한다
        deleteTryOn(coordinationId).andExpect(status().isNoContent)
    }

    /** 생성이 멱등이라 지우는 길이 없으면 잘못 나온 결과를 바꿀 수 없다. */
    @Test
    fun `가상 착용을 지우면 다시 만들 수 있다`() {
        val coordinationId = coordinationWithTryOn()
        assertEquals(1, tryOn.calls)

        deleteTryOn(coordinationId).andExpect(status().isNoContent)

        mockMvc.perform(post("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tryOnImageUrl").isString)
        assertEquals(2, tryOn.calls, "지운 뒤에는 생성기를 다시 불러야 한다")
    }

    @Test
    fun `남의 코디의 가상 착용 이미지는 지울 수 없다`() {
        val coordinationId = coordinationWithTryOn()

        mockMvc.perform(
            delete("/api/coordinations/$coordinationId/tryon").header(HttpHeaders.AUTHORIZATION, other.bearer),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))

        // 남이 요청했다고 내 이미지가 사라지면 안 된다
        assertNotNull(tryOnUrlOf(coordinationId))
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

    // --- 구조 보장

    /** `orbit.gemini.enabled=false` 설정이 실수로 바뀌면 여기서 먼저 깨진다. */
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
        mockMvc.perform(delete("/api/coordinations/1/tryon")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "a.png", MediaType.IMAGE_PNG_VALUE, pngBytes())),
        ).andExpect(status().isUnauthorized)
    }
}

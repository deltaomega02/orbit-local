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

    // ── 분석 ──────────────────────────────────────────────────────

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
        // 저장되지 않았다는 것이 이 엔드포인트의 계약이다
        assertEquals(0, clothesRepository.findAllByOwnerIdAndDeletedAtIsNull(me.userId).size)
        // 선언된 Content-Type 이 아니라 실제로 판별한 형식이 AI 로 넘어간다
        assertEquals("image/png", analyzer.lastMime)
    }

    /**
     * AI 로 보내는 사진은 **저장본보다 더 줄인다.**
     *
     * 이 바이트는 base64 로 4/3 배 부풀어 그대로 요청 크기와 토큰 비용이 된다.
     * 상한을 8MB 에서 40MB 로 연 지금 이 자리를 그냥 두면, 열어 준 만큼 AI 요금이
     * 따라 오른다. 옷의 종류·색·소재를 읽는 데 1600px 가 필요하지는 않다.
     *
     * 응답만 보면 원본을 그대로 실어 보내도 테스트는 통과하므로, 가짜가 받은 바이트를
     * 직접 디코드해서 본다.
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
        // 줄이면서 다시 구웠으므로 형식도 바뀐다. 실제로 보낸 바이트와 mime 이 어긋나면
        // 모델은 읽지 못하는 데이터를 받는다.
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

    // ── 추천 ──────────────────────────────────────────────────────

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
     *
     * 옷장을 상의1·하의2 로 두는 것이 이 테스트의 전제다. 상의1·하의1 이면 두 번째
     * 추천은 AI 를 부르기도 전에 `exhausted` 로 끊기므로(아래 테스트) 여기서 보려는
     * "불렀더니 하필 겹쳤다"가 성립하지 않는다. **아직 조합이 남아 있을 때만 duplicate 다.**
     */
    @Test
    fun `AI 가 오늘 나온 조합을 다시 내놓으면 409 와 retry true 다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        addClothes(me, "청바지", "BOTTOM") // 조합은 2가지 — 하나를 써도 아직 남는다
        recommend().andExpect(status().isCreated)

        recommender.behavior = { OutfitSuggestion("또 같은 코디", "같은 이유", listOf(top, bottom)) }

        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("duplicate"))
            .andExpect(jsonPath("$.retry").value(true))
            .andExpect(jsonPath("$.clothesIds.length()").value(2))

        assertEquals(2, recommender.calls, "남은 조합이 있으면 AI 를 부르는 것이 맞다")
    }

    /**
     * duplicate 와 exhausted 를 가르는 지점.
     *
     * 상의1·하의1 이면 가능한 조합은 **산수로 1개**다. 그런데도 두 번째 추천에서
     * AI 를 부르고 409 duplicate 를 받고 클라이언트가 재시도하기를 반복하면, 결과가
     * 정해져 있는 상태에서 호출 3번과 40초가 확정적으로 낭비된다. 신규 사용자가 가장
     * 먼저 마주치는 상태라 더 그렇다. 그래서 호출 **전에** 판정한다.
     */
    @Test
    fun `상의1 하의1 이면 두 번째 추천은 AI 를 부르지 않고 exhausted 다`() {
        addClothes(me, "셔츠", "TOP")
        addClothes(me, "슬랙스", "BOTTOM")

        recommend().andExpect(status().isCreated)
        assertEquals(1, recommender.calls)

        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("exhausted"))
            // duplicate 와 달리 재시도가 소용없다. 두 사건을 클라이언트가 구분할 수 있어야 한다.
            .andExpect(jsonPath("$.retry").value(false))
            .andExpect(jsonPath("$.detail").isString)

        assertEquals(1, recommender.calls, "가능한 조합이 없으면 AI 를 부르기 전에 끊는다")
    }

    /**
     * 조합 수의 기준이 실제 추천 규칙(상의1 · 하의1 · 아우터는 선택)과 맞는지 본다.
     * 아우터가 한 벌 들어오면 조합은 1×1×(1+1)=2 가지가 되므로, 두 번째 추천은
     * exhausted 가 아니어야 한다 — 여기서 막으면 사용자에게서 가능한 조합을 빼앗는다.
     */
    @Test
    fun `아우터는 선택이라 조합 수를 두 배로 늘린다`() {
        val top = addClothes(me, "셔츠", "TOP")
        val bottom = addClothes(me, "슬랙스", "BOTTOM")
        val outer = addClothes(me, "코트", "OUTER")

        // 1) 상의 + 하의
        recommend().andExpect(status().isCreated)

        // 2) 아직 상의 + 하의 + 아우터가 남아 있다. AI 를 불러야 한다.
        recommender.behavior = { OutfitSuggestion("코트 코디", "쌀쌀해서", listOf(top, bottom, outer)) }
        recommend().andExpect(status().isCreated)
        assertEquals(2, recommender.calls)

        // 3) 이제 두 가지를 다 썼다.
        recommend()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("exhausted"))
        assertEquals(2, recommender.calls)
    }

    /**
     * 수동 생성은 추천 규칙을 따르지 않는다(하의 없이도 만들 수 있다). 그런 코디를
     * 조합 수에 세면 아직 남은 조합이 있는데도 "다 봤다"고 막게 된다.
     */
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

        // 상의만 있는 코디는 추천이 만들 수 있는 조합(상의1+하의1)을 소비하지 않았다
        recommend().andExpect(status().isCreated)
        assertEquals(1, recommender.calls)
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

    /**
     * 가상 착용 결과가 엉뚱한 사람으로 나오는 일은 실제로 있다. 그때 사용자가 버리고
     * 싶은 것은 이미지 한 장이지 조합·추천 이유·LOOK 번호가 아니다. 이 엔드포인트가
     * 없던 동안 남은 선택지는 룩을 통째로 지우는 것뿐이었다.
     */
    @Test
    fun `가상 착용 이미지만 지우면 코디는 남고 tryOnImageUrl 이 null 로 돌아온다`() {
        val coordinationId = coordinationWithTryOn()
        val imageUrl = tryOnUrlOf(coordinationId) as String
        val storedPath = imageUrl.removePrefix("/media/")

        deleteTryOn(coordinationId).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/coordinations/$coordinationId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tryOnImageUrl").isEmpty)
            // 코디 자체는 살아 있어야 한다. 이미지를 지우는 것과 룩을 버리는 것은 다른 일이다.
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.lookNo").isNumber)

        // 아무도 참조하지 않는 파일을 남기지 않는다. 행만 지우면 고아 파일이 쌓인다.
        assertNull(mediaStorage.read(storedPath), "가상 착용 이미지 파일이 그대로 남았다")
    }

    @Test
    fun `가상 착용 이미지가 없어도 삭제는 204 다`() {
        val coordinationId = coordinationWithTryOn()

        deleteTryOn(coordinationId).andExpect(status().isNoContent)
        // 두 번째도 204. 결과 상태가 같으므로 404 를 줄 이유가 없다 —
        // 404 면 클라이언트가 지우기 전에 상태를 한 번 더 조회해야 한다.
        deleteTryOn(coordinationId).andExpect(status().isNoContent)
    }

    /** 생성이 멱등이라, 지우는 길이 없으면 한 번 잘못 나온 결과에 영영 묶인다. */
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
        mockMvc.perform(delete("/api/coordinations/1/tryon")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "a.png", MediaType.IMAGE_PNG_VALUE, pngBytes())),
        ).andExpect(status().isUnauthorized)
    }
}

package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * 코디 기록 — 목록·상세·삭제·즐겨찾기.
 *
 * `/today` 는 "오늘 뭘 입지"를 위한 화면이고 이쪽은 "그동안 뭘 입었지"를 위한
 * 화면이다. 목적이 다르므로 최신순 정렬과 페이지네이션이 실제로 동작하는지를
 * 눈으로 확인한다 — 설정만 있고 동작하지 않던 원본의 페이지네이션이 이 저장소가
 * 반복해서 확인하는 지점이다.
 *
 * [FakeAiConfig] 를 가져오는 이유는 가상 착용 이미지가 있어야 "코디를 지우면
 * 그 파일도 지워지는가"를 검증할 수 있기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfig::class)
@DisplayName("코디 기록 — 목록·상세·삭제·즐겨찾기")
class CoordinationHistoryApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var mediaStorage: MediaStorage

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session
    private var topId = 0L
    private var bottomId = 0L

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("history-me@orbit.test")
        other = api.signUpAndLogin("history-other@orbit.test")
        topId = createClothes(me, "셔츠", "TOP")
        bottomId = createClothes(me, "슬랙스", "BOTTOM")
    }

    private fun createClothes(session: Session, name: String, category: String): Long {
        val body = mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "$name.png", "image/png", pngBytes()))
                .param("name", name)
                .param("mainCategory", category)
                .header(HttpHeaders.AUTHORIZATION, session.bearer),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }

    private fun createCoordination(session: Session, title: String, ids: List<Long>): Long {
        val body = mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, session.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("title" to title, "clothesIds" to ids))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }

    /**
     * 여러 건을 만든다. 같은 날 같은 조합은 409 로 막히므로(중복 추천 계약) 옷을
     * 늘려 가며 서로 다른 조합을 만든다.
     */
    private fun createMany(count: Int): List<Long> = (1..count).map { i ->
        val extra = createClothes(me, "티셔츠$i", "TOP")
        createCoordination(me, "코디$i", listOf(extra, bottomId))
    }

    // ── 목록 ──────────────────────────────────────────────────────

    @Test
    fun `기록 목록은 최신순이다`() {
        val ids = createMany(3)

        mockMvc.perform(get("/api/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(3))
            // 마지막에 만든 것이 맨 위다
            .andExpect(jsonPath("$.content[0].id").value(ids[2]))
            .andExpect(jsonPath("$.content[1].id").value(ids[1]))
            .andExpect(jsonPath("$.content[2].id").value(ids[0]))
    }

    /**
     * 원본에서는 `PAGE_SIZE = 20` 설정이 있었지만 실제로는 항상 전체가 나왔다.
     * "설정했다"와 "동작한다"는 다르므로 21건을 넣고 두 페이지로 갈리는지 본다.
     */
    @Test
    fun `기본 페이지 크기는 20 이고 다음 페이지가 이어진다`() {
        createMany(21)

        mockMvc.perform(get("/api/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(20))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(21))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))

        mockMvc.perform(get("/api/coordinations?page=1").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `페이지가 겹치거나 빠지지 않는다`() {
        createMany(5)

        val first = pageIds(0, 2)
        val second = pageIds(1, 2)
        val third = pageIds(2, 2)

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(1, third.size)
        // 세 페이지를 합치면 전체가 되고, 같은 코디가 두 번 나오지 않는다
        val all = first + second + third
        assertEquals(5, all.toSet().size, "페이지 사이에 중복이 있으면 안 된다")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pageIds(page: Int, size: Int): List<Int> {
        val body = mockMvc.perform(
            get("/api/coordinations?page=$page&size=$size").header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val content = api.json(body)["content"] as List<Map<String, Any>>
        return content.map { it["id"] as Int }
    }

    @Test
    fun `size 는 상한을 넘길 수 없다`() {
        createMany(1)

        mockMvc.perform(get("/api/coordinations?size=1000000").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(100))
    }

    @Test
    fun `기록 목록에는 내 코디만 보인다`() {
        createCoordination(me, "내 코디", listOf(topId, bottomId))
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(get("/api/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[?(@.title == '남의 코디')]").isEmpty)
    }

    @Test
    fun `코디가 하나도 없으면 빈 페이지를 반환한다`() {
        mockMvc.perform(get("/api/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    // ── 상세 ──────────────────────────────────────────────────────

    @Test
    fun `상세 조회는 아이템까지 함께 준다`() {
        val id = createCoordination(me, "출근룩", listOf(topId, bottomId))

        mockMvc.perform(get("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("출근룩"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].imageUrl").isString)
            .andExpect(jsonPath("$.favorite").value(false))
    }

    @Test
    fun `남의 코디와 없는 코디는 똑같이 404 다`() {
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        val othersId = createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(get("/api/coordinations/$othersId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
        mockMvc.perform(get("/api/coordinations/999999").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    // ── 즐겨찾기 ──────────────────────────────────────────────────

    @Test
    fun `즐겨찾기는 두 번 누르면 원래대로 돌아온다`() {
        val id = createCoordination(me, "출근룩", listOf(topId, bottomId))

        mockMvc.perform(post("/api/coordinations/$id/favorite").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.favorite").value(true))
        mockMvc.perform(get("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.favorite").value(true))

        mockMvc.perform(post("/api/coordinations/$id/favorite").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.favorite").value(false))
        mockMvc.perform(get("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.favorite").value(false))
    }

    @Test
    fun `남의 코디는 즐겨찾기할 수 없다`() {
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        val othersId = createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(post("/api/coordinations/$othersId/favorite").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)

        assertEquals(
            false,
            requireNotNull(coordinationRepository.findById(othersId).orElse(null)).favorite,
            "남의 코디 상태가 바뀌면 안 된다",
        )
    }

    // ── 삭제 ──────────────────────────────────────────────────────

    @Test
    fun `삭제하면 204 이고 다시 조회하면 404 다`() {
        val id = createCoordination(me, "지울 코디", listOf(topId, bottomId))

        mockMvc.perform(delete("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `코디를 지워도 옷장의 옷은 남는다`() {
        val id = createCoordination(me, "지울 코디", listOf(topId, bottomId))

        mockMvc.perform(delete("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        // 코디를 지웠다고 옷장에서 옷이 사라지면 그게 더 놀라운 동작이다
        mockMvc.perform(get("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
    }

    /**
     * 가상 착용 이미지 정리.
     *
     * 코디를 지웠는데 파일이 남으면, 그 파일은 이제 아무도 참조하지 않으면서
     * 디스크만 차지하는 고아가 된다. `/media` 소유권 검사도 DB 를 근거로 하므로
     * 접근은 막히지만, 접근이 막히는 것과 지워지는 것은 다르다.
     */
    @Test
    fun `코디를 지우면 가상 착용 이미지 파일도 지워진다`() {
        val id = createCoordination(me, "입어볼 코디", listOf(topId, bottomId))
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/users/me/body-photo")
                .file(MockMultipartFile("image", "body.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isOk)
        mockMvc.perform(post("/api/coordinations/$id/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)

        val path = requireNotNull(coordinationRepository.findById(id).orElse(null)).tryOnImagePath
        assertNotNull(path, "가상 착용 이미지가 만들어져 있어야 이 테스트가 의미를 가진다")
        assertNotNull(mediaStorage.read(path), "삭제 전에는 파일이 있어야 한다")

        mockMvc.perform(delete("/api/coordinations/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        assertNull(mediaStorage.read(path), "코디를 지웠는데 가상 착용 이미지가 남으면 고아 파일이다")
    }

    @Test
    fun `남의 코디는 삭제되지 않는다`() {
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        val othersId = createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(delete("/api/coordinations/$othersId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)

        assertEquals(true, coordinationRepository.findById(othersId).isPresent, "남의 코디가 지워지면 안 된다")
    }

    @Test
    fun `토큰 없이는 기록에 손댈 수 없다`() {
        val id = createCoordination(me, "출근룩", listOf(topId, bottomId))

        mockMvc.perform(get("/api/coordinations")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/coordinations/$id")).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/coordinations/$id")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/coordinations/$id/favorite")).andExpect(status().isUnauthorized)
    }
}

package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import com.orbit.service.ClothesService
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * 옷장 통계와 옷별 코디 역참조.
 * 통계는 틀려도 오류 없이 숫자만 어긋나므로 실제 데이터를 만들어 하나씩 맞춘다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("옷장 통계 · 옷별 코디 역참조")
class ClothesStatsApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var clothesService: ClothesService
    @Autowired lateinit var entityManager: EntityManager

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("stats-me@orbit.test")
        other = api.signUpAndLogin("stats-other@orbit.test")
    }

    private fun createClothes(session: Session, name: String, category: String): Long {
        val body = mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, session.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to name, "mainCategory" to category))),
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

    // --- 통계

    @Test
    fun `옷이 하나도 없으면 전부 0 이다`() {
        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.byCategory.length()").value(0))
            .andExpect(jsonPath("$.mostUsed.length()").value(0))
            .andExpect(jsonPath("$.neverUsed").value(0))
    }

    /**
     * 옷 5벌(TOP 2, BOTTOM 2, OUTER 1), 코디 3건.
     * 셔츠 3회, 슬랙스 2회, 청바지·티셔츠 1회, 코트 0회라 neverUsed 는 1 이다.
     */
    @Test
    fun `통계 수치가 실제 데이터와 맞는다`() {
        val shirt = createClothes(me, "셔츠", "TOP")
        val tee = createClothes(me, "티셔츠", "TOP")
        val slacks = createClothes(me, "슬랙스", "BOTTOM")
        val jeans = createClothes(me, "청바지", "BOTTOM")
        createClothes(me, "코트", "OUTER")

        createCoordination(me, "코디1", listOf(shirt, slacks))
        createCoordination(me, "코디2", listOf(shirt, jeans))
        createCoordination(me, "코디3", listOf(shirt, slacks, tee))

        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.byCategory.TOP").value(2))
            .andExpect(jsonPath("$.byCategory.BOTTOM").value(2))
            .andExpect(jsonPath("$.byCategory.OUTER").value(1))
            .andExpect(jsonPath("$.mostUsed.length()").value(4))
            .andExpect(jsonPath("$.mostUsed[0].clothesId").value(shirt))
            .andExpect(jsonPath("$.mostUsed[0].name").value("셔츠"))
            .andExpect(jsonPath("$.mostUsed[0].usedCount").value(3))
            .andExpect(jsonPath("$.mostUsed[1].clothesId").value(slacks))
            .andExpect(jsonPath("$.mostUsed[1].usedCount").value(2))
            .andExpect(jsonPath("$.neverUsed").value(1))
    }

    @Test
    fun `한 번도 안 쓴 옷 수가 옷장 전체와 맞는다`() {
        createClothes(me, "안 입은 셔츠", "TOP")
        createClothes(me, "안 입은 바지", "BOTTOM")
        createClothes(me, "안 입은 코트", "OUTER")

        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.neverUsed").value(3))
            .andExpect(jsonPath("$.mostUsed.length()").value(0))
    }

    /** 화면이 한 줄로 보여주는 개수라 상위 5개로 자른다. */
    @Test
    fun `많이 입은 옷은 상위 5개까지만 준다`() {
        val bottom = createClothes(me, "슬랙스", "BOTTOM")
        repeat(7) { i ->
            val top = createClothes(me, "상의$i", "TOP")
            createCoordination(me, "코디$i", listOf(top, bottom))
        }

        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.total").value(8))
            .andExpect(jsonPath("$.mostUsed.length()").value(5))
            // 모든 코디에 들어간 슬랙스가 1위다
            .andExpect(jsonPath("$.mostUsed[0].clothesId").value(bottom))
            .andExpect(jsonPath("$.mostUsed[0].usedCount").value(7))
    }

    @Test
    fun `옷장에서 치운 옷은 통계에서 빠진다`() {
        val shirt = createClothes(me, "셔츠", "TOP")
        val slacks = createClothes(me, "슬랙스", "BOTTOM")
        createClothes(me, "안 입은 코트", "OUTER")
        createCoordination(me, "코디1", listOf(shirt, slacks))

        mockMvc.perform(delete("/api/clothes/$shirt").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        // 셔츠가 빠지고 슬랙스(1회)와 코트(0회)만 남는다
        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.byCategory.TOP").doesNotExist())
            .andExpect(jsonPath("$.mostUsed.length()").value(1))
            .andExpect(jsonPath("$.mostUsed[0].clothesId").value(slacks))
            .andExpect(jsonPath("$.neverUsed").value(1))
    }

    @Test
    fun `통계에 남의 옷은 섞이지 않는다`() {
        createClothes(me, "내 셔츠", "TOP")
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(get("/api/clothes/stats").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.mostUsed.length()").value(0))
            .andExpect(jsonPath("$.neverUsed").value(1))
    }

    /** 카테고리별 개수, 많이 입은 옷, 쓰인 옷 수로 3회. `neverUsed` 는 빼기로 구한다. */
    @Test
    fun `통계는 옷 수와 무관하게 쿼리 3회로 계산된다`() {
        val bottom = createClothes(me, "슬랙스", "BOTTOM")
        repeat(5) { i ->
            val top = createClothes(me, "상의$i", "TOP")
            createCoordination(me, "코디$i", listOf(top, bottom))
        }

        val stats = entityManager.entityManagerFactory
            .unwrap(SessionFactory::class.java).statistics
        stats.clear()

        val result = clothesService.stats(me.userId)

        assertEquals(6L, result.total)
        assertEquals(3, stats.prepareStatementCount)
    }

    @Test
    fun `토큰 없이 통계를 볼 수 없다`() {
        mockMvc.perform(get("/api/clothes/stats")).andExpect(status().isUnauthorized)
    }

    // --- 코디 역참조

    @Test
    fun `옷 상세의 코디 역참조는 그 옷이 쓰인 것만 돌려준다`() {
        val shirt = createClothes(me, "셔츠", "TOP")
        val tee = createClothes(me, "티셔츠", "TOP")
        val slacks = createClothes(me, "슬랙스", "BOTTOM")

        val withShirt1 = createCoordination(me, "셔츠 코디1", listOf(shirt, slacks))
        val withTee = createCoordination(me, "티셔츠 코디", listOf(tee, slacks))
        val withShirt2 = createCoordination(me, "셔츠 코디2", listOf(shirt, slacks, tee))

        mockMvc.perform(get("/api/clothes/$shirt/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            // 최신순
            .andExpect(jsonPath("$.content[0].id").value(withShirt2))
            .andExpect(jsonPath("$.content[1].id").value(withShirt1))
            .andExpect(jsonPath("$.content[?(@.id == $withTee)]").isEmpty)
            // 목록에서 바로 썸네일을 그리도록 아이템도 함께 준다
            .andExpect(jsonPath("$.content[0].items.length()").value(3))
    }

    @Test
    fun `한 번도 안 쓴 옷의 역참조는 빈 페이지다`() {
        val id = createClothes(me, "안 입은 셔츠", "TOP")

        mockMvc.perform(get("/api/clothes/$id/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `역참조도 페이지네이션이 동작한다`() {
        val slacks = createClothes(me, "슬랙스", "BOTTOM")
        repeat(3) { i ->
            val top = createClothes(me, "상의$i", "TOP")
            createCoordination(me, "코디$i", listOf(top, slacks))
        }

        mockMvc.perform(
            get("/api/clothes/$slacks/coordinations?page=0&size=2").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.hasNext").value(true))

        mockMvc.perform(
            get("/api/clothes/$slacks/coordinations?page=1&size=2").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    /** 빈 목록을 주면 그 id 가 실재한다는 사실이 새므로 없는 것과 같은 404 를 준다. */
    @Test
    fun `남의 옷 id 로 역참조하면 빈 목록이 아니라 404 다`() {
        val othersTop = createClothes(other, "남의 셔츠", "TOP")
        val othersBottom = createClothes(other, "남의 바지", "BOTTOM")
        createCoordination(other, "남의 코디", listOf(othersTop, othersBottom))

        mockMvc.perform(get("/api/clothes/$othersTop/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
        mockMvc.perform(get("/api/clothes/999999/coordinations").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    fun `토큰 없이 역참조를 볼 수 없다`() {
        val id = createClothes(me, "셔츠", "TOP")

        mockMvc.perform(get("/api/clothes/$id/coordinations")).andExpect(status().isUnauthorized)
    }
}

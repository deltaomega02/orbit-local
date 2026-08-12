package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * 의류 CRUD 와 소유권 격리.
 *
 * 두 사용자를 실제로 가입시켜 놓고, 남의 것에 손대는 요청이 전부 404 로 막히는지
 * 확인한다. 원본 Django 에서는 `user=request.user` 조건을 뷰마다 손으로 붙였기
 * 때문에 한 군데만 빠져도 여기가 뚫렸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("의류 API — CRUD·소유권 격리·페이지네이션")
class ClothesApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("me@orbit.test")
        other = api.signUpAndLogin("other@orbit.test")
    }

    private fun createClothes(
        session: Session,
        name: String,
        category: MainCategory = MainCategory.TOP,
        color: String? = null,
    ): Long {
        val body = mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, session.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("name" to name, "mainCategory" to category.name, "color" to color),
                    ),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }

    @Test
    fun `의류를 등록하면 201 과 생성된 리소스를 반환한다`() {
        mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("name" to "흰 셔츠", "mainCategory" to "TOP", "color" to "화이트"),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.name").value("흰 셔츠"))
            .andExpect(jsonPath("$.mainCategory").value("TOP"))
            .andExpect(jsonPath("$.color").value("화이트"))
    }

    @Test
    fun `등록한 의류의 소유자는 토큰의 주체다`() {
        val id = createClothes(me, "내 셔츠")

        // 요청 본문에 ownerId 를 넣을 자리가 없다는 것이 요점이다.
        // X-Owner-Id 시절에는 보내는 쪽이 소유자를 지정할 수 있었다.
        assertEquals(me.userId, requireNotNull(clothesRepository.findById(id).orElse(null)).ownerId)
    }

    @Test
    fun `목록에는 내 옷만 보인다`() {
        createClothes(me, "내 셔츠")
        createClothes(me, "내 바지", MainCategory.BOTTOM)
        createClothes(other, "남의 셔츠")

        mockMvc.perform(get("/api/clothes").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[?(@.name == '남의 셔츠')]").isEmpty)
    }

    /**
     * 원본 Django 에는 `PAGE_SIZE = 20` 설정이 있었지만 함수형 뷰라 페이지네이션이
     * 개입할 자리가 없었고, 목록은 항상 전체를 반환했다. 설정 파일에 값이 있다는 것과
     * 실제로 동작한다는 것은 다르므로, 21건을 넣고 눈으로 확인한다.
     */
    @Test
    fun `기본 페이지 크기는 20 이고 다음 페이지가 이어진다`() {
        repeat(21) { createClothes(me, "옷 $it") }

        mockMvc.perform(get("/api/clothes").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(20))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(21))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))

        mockMvc.perform(get("/api/clothes?page=1").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
    }

    @Test
    fun `page size 는 상한을 넘길 수 없다`() {
        createClothes(me, "셔츠")

        // 상한이 없으면 ?size=1000000 한 번으로 전체를 긁어갈 수 있어 페이지네이션이 무의미해진다
        mockMvc.perform(get("/api/clothes?size=1000000").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(100))
    }

    @Test
    fun `상세 조회`() {
        val id = createClothes(me, "코트", MainCategory.OUTER, "네이비")

        mockMvc.perform(get("/api/clothes/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("코트"))
            .andExpect(jsonPath("$.mainCategory").value("OUTER"))
    }

    /**
     * 남의 리소스에 403 이 아니라 404 를 주는 이유:
     * 403 은 "그 id 는 실재한다"를 알려준다. id 를 1부터 훑으면 남이 옷을 몇 벌
     * 가졌는지까지 셀 수 있다. 권한이 없는 쪽에서는 없는 것과 구별되지 않아야 한다.
     */
    @Test
    fun `남의 의류는 조회되지 않고 404 다`() {
        val othersId = createClothes(other, "남의 셔츠")

        mockMvc.perform(get("/api/clothes/$othersId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    fun `PATCH 는 보낸 필드만 바꾼다`() {
        val id = createClothes(me, "셔츠", MainCategory.TOP, "화이트")

        mockMvc.perform(
            patch("/api/clothes/$id")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to "린넨 셔츠"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("린넨 셔츠"))
            .andExpect(jsonPath("$.mainCategory").value("TOP"))
            .andExpect(jsonPath("$.color").value("화이트")) // 안 보낸 필드는 유지
    }

    @Test
    fun `남의 의류는 수정되지 않는다`() {
        val othersId = createClothes(other, "남의 셔츠")

        mockMvc.perform(
            patch("/api/clothes/$othersId")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to "탈취"))),
        ).andExpect(status().isNotFound)

        assertEquals("남의 셔츠", requireNotNull(clothesRepository.findById(othersId).orElse(null)).name)
    }

    @Test
    fun `삭제하면 204 이고 다시 조회하면 404 다`() {
        val id = createClothes(me, "버릴 셔츠")

        mockMvc.perform(delete("/api/clothes/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/clothes/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `남의 의류는 삭제되지 않는다`() {
        val othersId = createClothes(other, "남의 셔츠")

        mockMvc.perform(delete("/api/clothes/$othersId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)

        assertEquals(true, clothesRepository.findById(othersId).isPresent, "남의 옷이 지워지면 안 된다")
    }

    private fun createCoordination(title: String, ids: List<Long>): Long {
        val body = mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("title" to title, "clothesIds" to ids))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }

    /**
     * 삭제 규칙이 바뀐 자리다.
     *
     * 예전에는 코디에 물린 옷을 409(`clothes_in_use`)로 거절했다. 기록이 깨지는 건
     * 막았지만, "과거 기록을 보는 앱"에서 한 번 입은 옷을 영영 못 지우게 되는 쪽이
     * 더 이상했다. 지금은 소프트 삭제로 옷장에서만 감춘다 — 응답은 204 이고
     * 지난 코디는 그대로 남는다. 그 둘을 한 테스트에서 같이 확인한다.
     */
    @Test
    fun `코디에 쓰인 의류를 지우면 옷장에서만 사라지고 지난 코디는 남는다`() {
        val topId = createClothes(me, "셔츠", MainCategory.TOP)
        val bottomId = createClothes(me, "슬랙스", MainCategory.BOTTOM)
        val coordinationId = createCoordination("출근룩", listOf(topId, bottomId))

        mockMvc.perform(delete("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        // 옷장에서는 사라진다
        mockMvc.perform(get("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/clothes").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(jsonPath("$.content[?(@.name == '셔츠')]").isEmpty)
            .andExpect(jsonPath("$.totalElements").value(1))

        // 지난 코디는 그대로다. 여기가 깨지면 소프트 삭제를 택한 이유가 없어진다
        mockMvc.perform(get("/api/coordinations/$coordinationId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[?(@.name == '셔츠')]").isNotEmpty)

        // 행 자체는 남아 있다 — 그래야 위 코디가 성립한다
        assertEquals(true, clothesRepository.findById(topId).isPresent)
    }

    @Test
    fun `코디에 쓰인 적 없는 의류는 행까지 지운다`() {
        val id = createClothes(me, "한 번도 안 입은 셔츠")

        mockMvc.perform(delete("/api/clothes/$id").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        // 참조가 없으니 남길 이유가 없다. 소프트 삭제로 두면 아무도 안 보는 행만 쌓인다
        assertEquals(false, clothesRepository.findById(id).isPresent)
    }

    @Test
    fun `이미 지운 의류를 다시 지우면 404 다`() {
        val topId = createClothes(me, "셔츠", MainCategory.TOP)
        val bottomId = createClothes(me, "슬랙스", MainCategory.BOTTOM)
        createCoordination("출근룩", listOf(topId, bottomId))

        mockMvc.perform(delete("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)
        // 소프트 삭제라 행은 남지만, 사용자에게는 없는 옷과 구별되지 않아야 한다
        mockMvc.perform(delete("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `지운 의류로는 새 코디를 만들 수 없다`() {
        val topId = createClothes(me, "셔츠", MainCategory.TOP)
        val bottomId = createClothes(me, "슬랙스", MainCategory.BOTTOM)
        createCoordination("출근룩", listOf(topId, bottomId))
        mockMvc.perform(delete("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNoContent)

        // 과거 기록에는 남지만 "지금 입을 수 있는 옷"은 아니다. 없는 옷과 같이 취급한다
        mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("title" to "부활한 셔츠", "clothesIds" to listOf(topId, bottomId)),
                    ),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown_clothes"))
    }

    @Test
    fun `토큰 없이 CRUD 를 시도하면 전부 401`() {
        val id = createClothes(me, "셔츠")

        mockMvc.perform(get("/api/clothes")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/clothes/$id")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/clothes").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(patch("/api/clothes/$id").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/clothes/$id")).andExpect(status().isUnauthorized)
    }
}

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

    @Test
    fun `코디에 사용 중인 의류는 409 로 삭제를 거절한다`() {
        val topId = createClothes(me, "셔츠", MainCategory.TOP)
        val bottomId = createClothes(me, "슬랙스", MainCategory.BOTTOM)
        mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("title" to "출근룩", "clothesIds" to listOf(topId, bottomId)),
                    ),
                ),
        ).andExpect(status().isCreated)

        // 옷 한 벌을 지웠다고 지난 코디 기록이 사라지는 건 예상 밖의 동작이라, 막고 이유를 알린다
        mockMvc.perform(delete("/api/clothes/$topId").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("clothes_in_use"))
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

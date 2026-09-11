package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import com.orbit.web.CreateCoordinationRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 중복 추천에 대한 HTTP 계약을 고정한다.
 * 클라이언트는 409 + `retry: true` 를 보고 다른 조합으로 재시도한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("코디 API — 409 재시도 계약")
class CoordinationApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository

    private lateinit var session: Session
    private var topId = 0L
    private var bottomId = 0L

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()

        session = TestApiClient(mockMvc, objectMapper).signUpAndLogin("coordi@orbit.test")
        topId = clothesRepository.save(Clothes(session.userId, "셔츠", MainCategory.TOP)).id!!
        bottomId = clothesRepository.save(Clothes(session.userId, "슬랙스", MainCategory.BOTTOM)).id!!
    }

    private fun createRequest(title: String, ids: List<Long>) =
        post("/api/coordinations")
            .header(HttpHeaders.AUTHORIZATION, session.bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(CreateCoordinationRequest(title, ids)))

    @Test
    fun `처음 만드는 조합은 201 을 반환한다`() {
        mockMvc.perform(createRequest("출근룩", listOf(topId, bottomId)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("출근룩"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].layerOrder").value(0))
    }

    @Test
    fun `같은 조합을 다시 요청하면 409 와 retry true 를 반환한다`() {
        mockMvc.perform(createRequest("출근룩", listOf(topId, bottomId)))
            .andExpect(status().isCreated)

        mockMvc.perform(createRequest("출근룩 재추천", listOf(bottomId, topId)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("duplicate"))
            .andExpect(jsonPath("$.retry").value(true))
    }

    @Test
    fun `존재하지 않는 의류 id 는 400 을 반환한다`() {
        mockMvc.perform(createRequest("유령 코디", listOf(topId, 99999L)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown_clothes"))
    }

    @Test
    fun `오늘의 코디 목록을 조회한다`() {
        mockMvc.perform(createRequest("출근룩", listOf(topId, bottomId)))
            .andExpect(status().isCreated)

        mockMvc.perform(get("/api/coordinations/today").header(HttpHeaders.AUTHORIZATION, session.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].items.length()").value(2))
    }

    @Test
    fun `토큰 없이 코디를 만들 수 없다`() {
        // 예전 X-Owner-Id 헤더만으로는 인증되지 않아야 한다
        mockMvc.perform(
            post("/api/coordinations")
                .header("X-Owner-Id", session.userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateCoordinationRequest("훔친 코디", listOf(topId)))),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `남의 토큰으로는 내 코디 목록이 보이지 않는다`() {
        mockMvc.perform(createRequest("출근룩", listOf(topId, bottomId))).andExpect(status().isCreated)

        val intruder = TestApiClient(mockMvc, objectMapper).signUpAndLogin("intruder@orbit.test")

        mockMvc.perform(get("/api/coordinations/today").header(HttpHeaders.AUTHORIZATION, intruder.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}

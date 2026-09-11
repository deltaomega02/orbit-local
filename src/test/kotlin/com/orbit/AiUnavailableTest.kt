package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
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
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertTrue

/**
 * [FakeAiConfig] 를 가져오지 않아 컨텍스트에 AI 빈이 없다.
 * GEMINI_API_KEY 를 설정하지 않은 실제 환경과 같은 상태다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI 미설정 — AI 만 503, 나머지는 정상")
class AiUnavailableTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var context: ApplicationContext

    private lateinit var api: TestApiClient
    private lateinit var me: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("no-ai@orbit.test")
    }

    private fun addClothes(name: String, category: String) = mockMvc.perform(
        multipart("/api/clothes")
            .file(MockMultipartFile("image", "$name.png", "image/png", pngBytes()))
            .param("name", name)
            .param("mainCategory", category)
            .header(HttpHeaders.AUTHORIZATION, me.bearer),
    ).andExpect(status().isCreated)

    @Test
    fun `AI 빈이 없으면 추천은 503 이다`() {
        addClothes("셔츠", "TOP")
        addClothes("슬랙스", "BOTTOM")

        mockMvc.perform(post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("ai_unavailable"))
    }

    @Test
    fun `AI 빈이 없으면 사진 분석도 503 이다`() {
        mockMvc.perform(
            multipart("/api/clothes/analyze")
                .file(MockMultipartFile("image", "a.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("ai_unavailable"))
    }

    @Test
    fun `AI 가 없어도 옷장 기능은 전부 정상 동작한다`() {
        addClothes("셔츠", "TOP")

        mockMvc.perform(get("/api/clothes").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].imageUrl").isString)

        mockMvc.perform(
            multipart(org.springframework.http.HttpMethod.PUT, "/api/users/me/body-photo")
                .file(MockMultipartFile("image", "body.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bodyPhotoUrl").isString)
    }

    @Test
    fun `기본 컨텍스트에는 AI 구현이 하나도 등록되지 않는다`() {
        listOf(ClothingAnalyzer::class, OutfitRecommender::class, TryOnImageGenerator::class).forEach {
            assertTrue(
                context.getBeansOfType(it.java).isEmpty(),
                "${it.simpleName} 빈이 등록됐다 — 테스트가 실제 API 를 부를 수 있는 상태다",
            )
        }
    }
}

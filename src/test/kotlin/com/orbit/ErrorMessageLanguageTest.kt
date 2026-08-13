package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.domain.ClothesLimits
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertTrue

/**
 * 서버가 사용자에게 돌려주는 오류 문구의 언어.
 *
 * `detail` 은 화면이 그대로 띄우는 문장이다. 여기가 한국어로 남아 있으면 사용자는
 * 잘 돌아가는 화면 위에서 오류가 날 때만 읽지 못하는 글을 보게 된다 — 하필 도움이
 * 가장 필요한 순간이다.
 *
 * **`error` 코드는 건드리지 않는다.** 그건 사람이 읽는 값이 아니라 클라이언트가
 * 분기하는 값이라, 언어를 바꾸는 일과 무관해야 한다. 이 테스트는 그 분리도 함께
 * 확인한다 — 코드는 그대로, 문장만 일본어.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeAiConfig::class)
@DisplayName("오류 메시지 — 사람이 읽는 문장은 일본어, 코드는 그대로")
class ErrorMessageLanguageTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository

    private lateinit var api: TestApiClient
    private lateinit var me: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("errors@orbit.test")
    }

    /** 응답 본문에 한글 음절이 남아 있으면 그만큼 한국어 문구가 남아 있다는 뜻이다. */
    private fun assertNoKorean(result: ResultActions) {
        val body = result.andReturn().response.contentAsString
        val found = Regex("[가-힣]+").findAll(body).map { it.value }.toList()
        assertTrue(found.isEmpty(), "한국어가 남아 있다: $found — 본문: $body")
    }

    @Test
    fun `없는 옷을 조회하면 일본어 문구가 나온다`() {
        val result = mockMvc.perform(
            get("/api/clothes/999999").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isNotFound)
            // 코드는 클라이언트가 분기하는 값이라 그대로다
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.detail").value("服が見つかりません"))

        assertNoKorean(result)
    }

    @Test
    fun `토큰 없이 부르면 인증 오류도 일본어다`() {
        assertNoKorean(
            mockMvc.perform(get("/api/clothes/1"))
                .andExpect(status().isUnauthorized)
                // 토큰이 아예 없는 것은 "토큰이 틀렸다"와 다른 사건이라 코드도 다르다
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.detail").value("認証が必要です")),
        )
    }

    @Test
    fun `상의나 하의가 없으면 추천 거절 문구가 일본어다`() {
        val result = mockMvc.perform(
            post("/api/coordinations/recommend").header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("not_enough_clothes"))

        assertNoKorean(result)
    }

    /** 프레임워크 기본 문구는 JVM 로케일을 따라간다. 그래서 직접 적어 두었다. */
    @Test
    fun `입력 검증 실패 문구가 일본어다`() {
        val result = mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "シャツ",
                            "mainCategory" to "TOP",
                            "season" to "冬".repeat(ClothesLimits.SEASON + 1),
                        ),
                    ),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))

        assertNoKorean(result)
    }

    /** multipart 경로는 `@Valid` 가 걸리지 않아 손으로 맞춘 검사가 문구를 만든다. */
    @Test
    fun `multipart 경로의 검증 문구도 일본어다`() {
        val result = mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "shirt.png", "image/png", pngBytes()))
                .param("name", "シャツ")
                .param("mainCategory", "TOP")
                .param("fit", "ゆ".repeat(ClothesLimits.FIT + 1))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))

        assertNoKorean(result)
    }

    @Test
    fun `지원하지 않는 이미지 형식 문구도 일본어다`() {
        val result = mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "note.txt", "text/plain", "これは画像ではない".toByteArray()))
                .param("name", "シャツ")
                .param("mainCategory", "TOP")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_image_type"))

        assertNoKorean(result)
    }

    /**
     * 가짜 AI 를 넣어 둔 컨텍스트다([FakeAiConfig]). 그래야 503(AI 없음)이 아니라
     * 실제로 확인하려는 400(전신 사진 없음)까지 도달한다.
     */
    @Test
    fun `전신 사진 없이 가상 착용을 부르면 안내가 일본어다`() {
        val top = createClothes("シャツ", "TOP")
        val bottom = createClothes("デニム", "BOTTOM")
        val coordination = mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("title" to "今日のコーデ", "clothesIds" to listOf(top, bottom)),
                    ),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val id = api.json(coordination)["id"].toString().toLong()

        assertNoKorean(
            mockMvc.perform(post("/api/coordinations/$id/tryon").header(HttpHeaders.AUTHORIZATION, me.bearer))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("no_body_photo"))
                .andExpect(jsonPath("$.detail").value("先に全身写真を登録してください")),
        )
    }

    private fun createClothes(name: String, category: String): Long {
        val body = mockMvc.perform(
            post("/api/clothes")
                .header(HttpHeaders.AUTHORIZATION, me.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(mapOf("name" to name, "mainCategory" to category)),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return api.json(body)["id"].toString().toLong()
    }
}

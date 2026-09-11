package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * Gemini 키는 계정이 아니라 기기 단위다. 근거는 [com.orbit.ai.gemini.GeminiKeyStore] 참고.
 * `PUT` 은 사용자 데이터 폴더의 실제 `gemini.key` 를 덮어쓰므로 읽기만 한다.
 * 키 유무는 기기 상태라 단언하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Gemini 키 — 계정이 아니라 기기 단위")
class GeminiKeyScopeTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var api: TestApiClient

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
    }

    private fun statusFor(session: Session): String = mockMvc.perform(
        get("/api/settings/gemini-key").header(HttpHeaders.AUTHORIZATION, session.bearer),
    ).andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `키 상태는 계정이 달라도 같다 — 앱 전역 설정이다`() {
        val first = api.signUpAndLogin("key-first@orbit.test")
        val second = api.signUpAndLogin("key-second@orbit.test")

        assertEquals(
            statusFor(first),
            statusFor(second),
            "키는 계정별이 아니라 이 기기의 Orbit 전체에 적용된다. " +
                "이 단언이 깨졌다면 키 범위가 바뀐 것이고, 화면 문구와 README 도 함께 고쳐야 한다.",
        )
    }

    @Test
    fun `키 설정은 인증 뒤에 있다`() {
        // 열어 두면 같은 네트워크의 누구나 키를 바꾸거나 지울 수 있다
        mockMvc.perform(get("/api/settings/gemini-key")).andExpect(status().isUnauthorized)
    }
}

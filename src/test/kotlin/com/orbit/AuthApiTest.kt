package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 가입·로그인·재발급의 HTTP 계약.
 *
 * 원본 Django 는 `Authorization: Token someone@gmail.com` 한 줄이면 그 사람이 됐다.
 * 여기서 확인하려는 것은 "비밀번호를 아는 사람만 토큰을 받고, 토큰은 서버가 서명한
 * 것만 유효하다"는 두 가지다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("인증 API — 가입·로그인·재발급")
class AuthApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var api: TestApiClient

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
    }

    private fun signUpRequest(email: String, password: String = TEST_PASSWORD) =
        post("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    mapOf("email" to email, "password" to password, "displayName" to "테스터"),
                ),
            )

    private fun loginRequest(email: String, password: String) =
        post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password)))

    @Test
    fun `회원가입은 201 과 사용자 정보를 반환한다`() {
        mockMvc.perform(signUpRequest("a@orbit.test"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("a@orbit.test"))
            .andExpect(jsonPath("$.id").isNumber)
            // 응답에 비밀번호 관련 필드가 섞여 나가면 안 된다
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
    }

    @Test
    fun `비밀번호는 평문으로 저장되지 않는다`() {
        mockMvc.perform(signUpRequest("hash@orbit.test")).andExpect(status().isCreated)

        val stored = requireNotNull(userRepository.findByEmail("hash@orbit.test")).passwordHash

        assertNotEquals(TEST_PASSWORD, stored, "평문이 그대로 들어가 있으면 안 된다")
        assertTrue(stored.startsWith("\$2"), "BCrypt 해시는 \$2a/\$2b 접두사를 가진다: $stored")
        assertTrue(passwordEncoder.matches(TEST_PASSWORD, stored), "해시는 원문과 대조 가능해야 한다")
    }

    @Test
    fun `같은 이메일로 다시 가입하면 409 를 반환한다`() {
        mockMvc.perform(signUpRequest("dup@orbit.test")).andExpect(status().isCreated)

        mockMvc.perform(signUpRequest("dup@orbit.test"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("duplicate_email"))
    }

    @Test
    fun `이메일 대소문자가 달라도 같은 계정으로 본다`() {
        mockMvc.perform(signUpRequest("Case@Orbit.test")).andExpect(status().isCreated)

        mockMvc.perform(signUpRequest("case@orbit.test")).andExpect(status().isConflict)
        // 저장된 이메일은 정규화된 소문자여야 한다
        assertEquals(1, userRepository.count())
    }

    @Test
    fun `너무 짧은 비밀번호는 400 을 반환한다`() {
        mockMvc.perform(signUpRequest("short@orbit.test", password = "1234"))
            .andExpect(status().isBadRequest)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun `로그인하면 액세스 토큰과 리프레시 토큰을 함께 받는다`() {
        mockMvc.perform(signUpRequest("login@orbit.test")).andExpect(status().isCreated)

        mockMvc.perform(loginRequest("login@orbit.test", TEST_PASSWORD))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900)) // 15분
    }

    @Test
    fun `비밀번호가 틀리면 401 을 반환한다`() {
        mockMvc.perform(signUpRequest("wrong@orbit.test")).andExpect(status().isCreated)

        mockMvc.perform(loginRequest("wrong@orbit.test", "완전히-틀린-비밀번호"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
    }

    @Test
    fun `없는 이메일과 틀린 비밀번호의 응답이 구별되지 않는다`() {
        mockMvc.perform(signUpRequest("exists@orbit.test")).andExpect(status().isCreated)

        val wrongPassword = mockMvc.perform(loginRequest("exists@orbit.test", "틀린-비밀번호"))
            .andReturn().response
        val noSuchUser = mockMvc.perform(loginRequest("nobody@orbit.test", "틀린-비밀번호"))
            .andReturn().response

        // 응답이 다르면 로그인 API 가 곧 "가입 여부 조회 API" 가 된다.
        assertEquals(wrongPassword.status, noSuchUser.status)
        assertEquals(wrongPassword.contentAsString, noSuchUser.contentAsString)
    }

    @Test
    fun `리프레시 토큰으로 액세스 토큰을 재발급받는다`() {
        val session = api.signUpAndLogin("refresh@orbit.test")

        val body = mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refreshToken" to session.refreshToken))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            // 회전하지 않으므로 새 리프레시 토큰은 내주지 않는다 — 만료 상한(14일)을 지키기 위해서다
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn().response.contentAsString

        val reissued = api.json(body)["accessToken"] as String
        // 재발급받은 토큰이 실제로 보호된 경로에서 통해야 한다 — 여기까지 봐야 "재발급 성공"이다
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/coordinations/today").header("Authorization", "Bearer $reissued"),
        ).andExpect(status().isOk)
    }
}

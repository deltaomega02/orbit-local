package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/** 로그인한 사용자 한 명. 테스트에서 "누가" 요청하는지를 한 값으로 들고 다닌다. */
data class Session(
    val userId: Long,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
) {
    val bearer: String get() = "Bearer $accessToken"
}

const val TEST_PASSWORD = "orbit-test-1234"

/**
 * 테스트는 인증을 흉내 내지 않고 실제 가입·로그인을 거쳐 진짜 토큰을 받는다.
 * `@WithMockUser` 로 컨텍스트를 주입하면 필터·서명·만료 검증이 통째로 건너뛰어져서,
 * 정작 검증하려는 인증 경로가 테스트에서 빠진다.
 */
class TestApiClient(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {

    fun signUp(email: String, password: String = TEST_PASSWORD, displayName: String = "테스터"): Long {
        val body = mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("email" to email, "password" to password, "displayName" to displayName),
                    ),
                ),
        ).andReturn().response.contentAsString
        return json(body)["id"].toString().toLong()
    }

    fun login(email: String, password: String = TEST_PASSWORD): Session {
        val body = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        ).andReturn().response.contentAsString
        val parsed = json(body)
        return Session(
            userId = 0L, // 로그인 응답은 id 를 주지 않는다. 필요하면 signUpAndLogin 을 쓴다.
            email = email,
            accessToken = parsed["accessToken"] as String,
            refreshToken = parsed["refreshToken"] as String,
        )
    }

    fun signUpAndLogin(email: String, password: String = TEST_PASSWORD): Session {
        val id = signUp(email, password)
        return login(email, password).copy(userId = id)
    }

    @Suppress("UNCHECKED_CAST")
    fun json(body: String): Map<String, Any> = objectMapper.readValue(body, Map::class.java) as Map<String, Any>
}

package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import com.orbit.security.JwtProperties
import com.orbit.security.JwtTokenProvider
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * 토큰을 공격자 입장에서 다뤄본다.
 *
 * 원본 Django 의 토큰은 이메일 문자열 그 자체였다. 서명이 없으니 "위조"라는 개념이
 * 없었고, 만료가 없으니 한 번 새면 영구했다. 아래 테스트들은 그 두 결함이 지금은
 * 어떻게 막히는지를 하나씩 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("토큰 검증 — 위조·만료·종류 혼용")
class TokenSecurityTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var jwtProperties: JwtProperties

    private lateinit var api: TestApiClient
    private lateinit var session: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        session = api.signUpAndLogin("owner@orbit.test")
    }

    /** 보호된 경로면 어디든 결과가 같아야 한다. 인증은 엔드포인트별 코드가 아니라 필터의 책임이므로. */
    private fun protectedRequest(token: String?) =
        get("/api/coordinations/today").apply { token?.let { header(HttpHeaders.AUTHORIZATION, it) } }

    @Test
    fun `토큰 없이 보호된 엔드포인트에 접근하면 401`() {
        mockMvc.perform(protectedRequest(null))
            .andExpect(status().isUnauthorized)
            // 시큐리티 필터가 낸 401 도 JSON 이어야 한다(서블릿 기본 HTML 이면 클라이언트가 파싱에 실패)
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }

    @Test
    fun `Bearer 스킴이 아닌 헤더는 인증으로 인정되지 않는다`() {
        // 원본에서 쓰던 모양(`Token <이메일>`)을 그대로 보내본다
        mockMvc.perform(protectedRequest("Token owner@orbit.test"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `서명이 변조된 토큰은 401`() {
        val (header, payload, signature) = session.accessToken.split(".")
        val flipped = signature.mapIndexed { i, c -> if (i == 0) if (c == 'A') 'B' else 'A' else c }
            .joinToString("")

        mockMvc.perform(protectedRequest("Bearer $header.$payload.$flipped"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `페이로드의 sub 를 남의 id 로 바꾸면 401`() {
        // 권한 상승 시도: 서명은 그대로 두고 주체만 바꾼다.
        // 원본 Django 였다면 이 조작이 "다른 이메일을 적는 것"과 같아서 그냥 통했다.
        val (header, payload, signature) = session.accessToken.split(".")
        val decoded = String(Base64.getUrlDecoder().decode(payload))
        val tampered = decoded.replace("\"sub\":\"${session.userId}\"", "\"sub\":\"${session.userId + 1}\"")
        val reEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.toByteArray())

        mockMvc.perform(protectedRequest("Bearer $header.$reEncoded.$signature"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `다른 시크릿으로 서명한 토큰은 401`() {
        // 형식은 완벽하고 만료도 남았지만 서버 키로 서명되지 않았다.
        val attacker = JwtTokenProvider(
            jwtProperties.copy(secret = "attacker-controlled-secret-key-0123456789abcdef"),
            Clock.systemUTC(),
        )
        val forged = attacker.issueAccessToken(session.userId, session.email)

        mockMvc.perform(protectedRequest("Bearer $forged"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `만료된 토큰은 401`() {
        // Clock 을 과거로 고정해 발급하면, 유효기간 15분이 이미 지난 토큰이 나온다.
        // 테스트가 15분을 기다리지 않아도 되는 것이 Clock 을 주입해 둔 이유다.
        val past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC)
        val expired = JwtTokenProvider(jwtProperties, past).issueAccessToken(session.userId, session.email)

        mockMvc.perform(protectedRequest("Bearer $expired"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))
    }

    @Test
    fun `리프레시 토큰으로는 API 를 호출할 수 없다`() {
        // 두 토큰은 같은 키로 서명되므로 서명만 봐서는 구분되지 않는다.
        // 종류를 확인하지 않으면 14일짜리 토큰이 그대로 API 키가 된다.
        mockMvc.perform(protectedRequest("Bearer ${session.refreshToken}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `액세스 토큰으로 재발급을 요청하면 401`() {
        // 반대 방향의 혼용. 이걸 허용하면 액세스 토큰만으로 무한히 갱신할 수 있어
        // "15분"이라는 노출 상한이 사라진다.
        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refreshToken" to session.accessToken))),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_token"))
    }

    @Test
    fun `탈퇴한 사용자의 리프레시 토큰은 재발급되지 않는다`() {
        userRepository.deleteAll()

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refreshToken" to session.refreshToken))),
        ).andExpect(status().isUnauthorized)
    }
}

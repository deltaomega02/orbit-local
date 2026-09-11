package com.orbit.web

import com.orbit.service.AuthService
import com.orbit.service.IssuedTokens
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 화면에서 쓰지 않는 경로라 검증 메시지는 프레임워크 기본 문구(JVM 로케일)를 쓴다.
// TODO: 로그인 화면을 되살리면 CreateClothesRequest 처럼 일본어 메시지를 직접 적을 것
data class SignUpRequest(
    @field:Email @field:NotBlank val email: String,
    // NIST 800-63B 권고대로 길이만 강제하고 문자 조합 규칙은 두지 않는다
    @field:Size(min = 8, max = 72) val password: String,
    @field:Size(max = 40) val displayName: String = "",
)

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class UserResponse(val id: Long, val email: String, val displayName: String)

/**
 * `expiresIn` 은 초 단위.
 * refresh 토큰은 회전하지 않으므로 재발급 응답에서는 `refreshToken` 이 null 이다.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
) {
    companion object {
        fun from(t: IssuedTokens) = TokenResponse(t.accessToken, t.refreshToken, expiresIn = t.expiresIn)
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
) {

    /**
     * 자격증명 없이 앱 주인의 토큰을 발급한다. 응답 모양은 [login] 과 같다.
     * 서버가 루프백에만 뜨는 것이 전제이며, 같은 기기의 다른 프로세스는 막지 못한다.
     */
    @PostMapping("/session")
    fun session(): TokenResponse = TokenResponse.from(authService.startOwnerSession())

    // signup / login / refresh 는 화면에서 쓰지 않지만 소유권·/media 보호 테스트가 이 경로로 토큰을 받는다

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<UserResponse> {
        val user = authService.signUp(request.email, request.password, request.displayName)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse(requireNotNull(user.id), user.email, user.displayName),
        )
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        TokenResponse.from(authService.login(request.email, request.password))

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse =
        TokenResponse.from(authService.refresh(request.refreshToken))
}

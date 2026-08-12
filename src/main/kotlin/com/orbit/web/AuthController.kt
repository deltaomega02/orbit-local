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

data class SignUpRequest(
    @field:Email @field:NotBlank val email: String,
    // 길이만 강제한다. 특수문자 조합 규칙은 사용자를 예측 가능한 패턴("Password1!")으로
    // 몰아넣어 오히려 약해진다는 게 NIST 800-63B 의 권고다.
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
 * 토큰 응답. `expiresIn` 은 초 단위로, 클라이언트가 만료 직전에 미리 갱신할 수 있게 준다.
 * 재발급 응답에는 `refreshToken` 이 null 이라 JSON 에서 빠진다(회전하지 않기 때문).
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

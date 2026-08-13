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

/*
 * 아래 세 요청 DTO 만 검증 메시지를 일본어로 적지 않았다.
 *
 * 화면이 부르지 않는 경로이기 때문이다(가입·로그인은 자동 세션으로 대체됐다).
 * 지금 이 메시지를 볼 수 있는 것은 curl 을 직접 치는 개발자뿐이고, 프레임워크
 * 기본 문구로 충분하다. 화면에 로그인을 되살리는 날 [CreateClothesRequest] 처럼
 * 문구를 직접 적어야 한다 — 기본 문구는 JVM 로케일을 따라가서 언어가 실행 환경에
 * 달리기 때문이다.
 */
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

    /**
     * 자동 세션. **본문도 자격증명도 없다.** 부르면 이 앱의 주인
     * ([com.orbit.service.OwnerAccountService]) 토큰이 나온다.
     *
     * 화면은 앱을 열 때 이것 하나만 부르고 곧바로 옷장으로 간다. 한 사람이 자기
     * 노트북에서만 쓰는 앱에서 로그인 화면은 "본인임을 증명하는 절차"가 아니라
     * 매번 지나야 하는 문턱일 뿐이기 때문이다.
     *
     * **그 대가와 전제는 [com.orbit.service.AuthService.startOwnerSession] 주석에
     * 적어 두었다.** 요약하면 서버가 루프백에만 뜨는 것이 이 엔드포인트의 안전
     * 장치이고, 같은 기기의 다른 프로세스까지 막지는 못한다.
     *
     * 응답 모양은 [login] 과 같다. 클라이언트가 토큰을 다루는 코드를 하나로 유지할
     * 수 있고, 나중에 로그인을 되살릴 때 화면이 바꿀 것이 "무엇을 부르는가" 하나뿐이다.
     */
    @PostMapping("/session")
    fun session(): TokenResponse = TokenResponse.from(authService.startOwnerSession())

    /*
     * 아래 signup / login / refresh 는 **현재 화면에서 쓰지 않는다.** 지우지 않은
     * 이유는 두 가지다.
     *  1) 소유권 격리와 `/media` 보호가 전부 이 토큰 위에 서 있고, 그 동작을
     *     고정하는 테스트가 이 경로들로 진짜 토큰을 받아서 쓴다. 경로를 지우면
     *     검증이 함께 사라진다.
     *  2) 여러 사람이 쓰게 되는 날 되살릴 경로다. 지웠다 다시 만드는 것보다
     *     남겨 두고 화면에서 부르지 않는 편이 되돌리기 쉽다.
     */

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

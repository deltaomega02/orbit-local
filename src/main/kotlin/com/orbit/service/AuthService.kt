package com.orbit.service

import com.orbit.domain.User
import com.orbit.repository.UserRepository
import com.orbit.security.InvalidTokenException
import com.orbit.security.JwtTokenProvider
import com.orbit.security.TokenType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class EmailAlreadyUsedException(val email: String) : RuntimeException("이미 가입된 이메일입니다: $email")

/** 계정 존재 여부를 드러내지 않도록 이메일 없음과 비밀번호 오류를 구분하지 않는다. */
class InvalidCredentialsException : RuntimeException("이메일 또는 비밀번호가 올바르지 않습니다")

data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenProvider: JwtTokenProvider,
    private val ownerAccountService: OwnerAccountService,
) {

    /**
     * 자격증명 없이 앱 주인([OwnerAccountService])에게 토큰을 발급한다(`POST /api/auth/session`).
     * 보안 주의: `server.address` 가 루프백(127.0.0.1)이라는 전제에서만 안전하다.
     * 같은 기기의 다른 프로세스도 토큰을 받을 수 있다. 외부 접근이 필요해지면 [login] 을 다시 쓴다.
     */
    @Transactional
    fun startOwnerSession(): IssuedTokens = issue(ownerAccountService.resolveOrCreate())

    /** 동시 가입은 사전 조회를 통과할 수 있어 DB 유니크 제약 위반도 같은 409 로 변환한다. */
    @Transactional
    fun signUp(email: String, rawPassword: String, displayName: String): User {
        val normalized = email.trim().lowercase()
        if (userRepository.existsByEmail(normalized)) throw EmailAlreadyUsedException(normalized)
        return try {
            userRepository.saveAndFlush(
                User(
                    email = normalized,
                    passwordHash = passwordEncoder.encode(rawPassword),
                    displayName = displayName.ifBlank { normalized.substringBefore('@') },
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw EmailAlreadyUsedException(normalized)
        }
    }

    @Transactional(readOnly = true)
    fun login(email: String, rawPassword: String): IssuedTokens {
        val user = userRepository.findByEmail(email.trim().lowercase())
        // 알려진 한계: 사용자가 없으면 해시 비교를 건너뛰어 응답 시간 차이가 생긴다.
        if (user == null || !passwordEncoder.matches(rawPassword, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        return issue(user)
    }

    /** 액세스 + 리프레시 토큰 한 쌍을 발급한다. */
    private fun issue(user: User): IssuedTokens {
        val id = requireNotNull(user.id)
        return IssuedTokens(
            accessToken = tokenProvider.issueAccessToken(id, user.email),
            refreshToken = tokenProvider.issueRefreshToken(id, user.email),
            expiresIn = tokenProvider.accessTokenValiditySeconds,
        )
    }

    /**
     * 리프레시 토큰으로 액세스 토큰만 재발급한다. 폐기 저장소가 없어 리프레시 토큰은 회전하지 않는다(14일 상한 유지).
     * 두 토큰이 같은 키로 서명되므로 [TokenType] 을 반드시 검사한다.
     */
    @Transactional(readOnly = true)
    fun refresh(refreshToken: String): IssuedTokens {
        val principal = tokenProvider.parse(refreshToken, TokenType.REFRESH)
        // 그 사이 탈퇴했을 수 있어 재발급 때만 DB 를 확인한다(액세스 토큰 검증은 무상태).
        val user = userRepository.findById(principal.id).orElseThrow {
            InvalidTokenException("유효하지 않은 토큰입니다")
        }
        return IssuedTokens(
            accessToken = tokenProvider.issueAccessToken(requireNotNull(user.id), user.email),
            refreshToken = null,
            expiresIn = tokenProvider.accessTokenValiditySeconds,
        )
    }
}

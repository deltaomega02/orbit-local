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

/** 이메일이 없든 비밀번호가 틀렸든 같은 예외를 쓴다. 계정 존재 여부를 알려주지 않기 위해서다. */
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
) {

    /**
     * 가입. 비밀번호는 이 메서드를 통과하는 순간 해시가 되고, 평문은 어디에도 남지 않는다.
     *
     * 이메일 중복은 사전 조회로 한 번 걸러도 동시 요청에서는 통과할 수 있으므로,
     * 최종 방어선은 DB 의 유니크 제약이다. 제약 위반도 같은 409 로 변환한다.
     */
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
        // 사용자가 없어도 해시 비교를 건너뛰지 않는다면 응답 시간으로 계정 존재 여부를
        // 추측하기 어려워진다. 다만 여기서는 단순함을 택하고, 응답 본문은 동일하게 맞춘다.
        if (user == null || !passwordEncoder.matches(rawPassword, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        val id = requireNotNull(user.id)
        return IssuedTokens(
            accessToken = tokenProvider.issueAccessToken(id, user.email),
            refreshToken = tokenProvider.issueRefreshToken(id, user.email),
            expiresIn = tokenProvider.accessTokenValiditySeconds,
        )
    }

    /**
     * 리프레시 토큰으로 액세스 토큰만 재발급한다.
     *
     * 리프레시 토큰을 함께 갱신(회전)하지 않는 이유: 폐기 저장소 없이 새 리프레시를
     * 내주면 만료가 무한히 밀려서 "14일"이라는 상한이 사실상 사라진다. 회전을 하려면
     * 이전 토큰을 무효화할 저장소(jti 블랙리스트 등)가 먼저 필요하고, 그건 이 저장소의
     * 범위 밖이라 지금은 상한을 지키는 쪽을 택했다.
     *
     * 액세스 토큰으로 이 메서드를 호출하면 [TokenType] 검증에서 걸린다. 두 토큰이
     * 같은 키로 서명되므로 서명만으로는 구분되지 않고, 종류를 안 보면 15분짜리
     * 토큰이 14일짜리 권한으로 승격된다.
     */
    @Transactional(readOnly = true)
    fun refresh(refreshToken: String): IssuedTokens {
        val principal = tokenProvider.parse(refreshToken, TokenType.REFRESH)
        // 토큰은 유효하지만 그 사이 탈퇴했을 수 있다. 재발급은 요청량이 적으므로
        // 여기서만 DB 를 확인한다(매 요청 확인은 액세스 토큰 검증을 무상태로 두기 위해 하지 않는다).
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

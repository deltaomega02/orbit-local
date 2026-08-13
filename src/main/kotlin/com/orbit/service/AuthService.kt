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
    private val ownerAccountService: OwnerAccountService,
) {

    /**
     * 자격증명 없이 **이 앱의 주인**([OwnerAccountService])에게 토큰을 발급한다.
     * `POST /api/auth/session` 이 부른다.
     *
     * ## 이것은 인증을 여는 결정이다
     *
     * 이 메서드는 "요청한 사람이 누구인지 확인하지 않고 토큰을 준다". 서버에 닿을 수
     * 있는 쪽은 전부 주인의 옷장을 읽고 쓸 수 있다는 뜻이다. 그래도 괜찮다고 보는
     * 전제는 딱 하나 — **서버가 이 기기 밖에서 보이지 않는다**는 것이다. 그래서
     * `server.address` 를 루프백(127.0.0.1)으로 못박아 두었다. 그 설정이 풀리는
     * 순간 이 메서드는 같은 네트워크 아무에게나 열린 문이 된다.
     *
     * 루프백 안에서도 공짜는 아니다. **같은 기기의 다른 프로세스**(브라우저 확장,
     * 다른 앱, 이 계정으로 도는 스크립트)는 여전히 이 엔드포인트를 부를 수 있고,
     * 부르면 주인의 토큰을 받는다. 비밀번호를 물었을 때와 비교해 실제로 잃는 것이
     * 정확히 그 차이다. 옷 사진과 코디 기록이 담긴 1인용 로컬 앱에서, 켤 때마다
     * 로그인 화면이 주는 마찰보다 이 위험이 작다고 판단했다. 여러 사람이 쓰거나
     * 기기 밖에서 접근하게 되면 이 판단은 무효다 — 그때는 아래 [login] 을 화면에
     * 다시 붙이면 된다. 그래서 지우지 않고 남겨 두었다.
     */
    @Transactional
    fun startOwnerSession(): IssuedTokens = issue(ownerAccountService.resolveOrCreate())

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
        return issue(user)
    }

    /** 액세스 + 리프레시 한 쌍. 로그인과 자동 세션이 같은 코드를 지나게 한다. */
    private fun issue(user: User): IssuedTokens {
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

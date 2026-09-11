package com.orbit.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

/**
 * access/refresh 토큰 혼용을 막기 위해 클레임에 넣고 검증한다.
 * `typ` 은 JOSE 헤더 표준 파라미터와 겹치므로 클레임 이름은 `token_type`.
 */
enum class TokenType(val claimValue: String) {
    ACCESS("access"),
    REFRESH("refresh"),
    ;

    companion object {
        fun from(value: String?): TokenType? = entries.firstOrNull { it.claimValue == value }
    }
}

class InvalidTokenException(message: String) : RuntimeException(message)

@ConfigurationProperties(prefix = "orbit.jwt")
data class JwtProperties(
    val secret: String,
    // 무상태 검증이라 탈퇴·차단 반영이 이 시간만큼 늦을 수 있어 짧게 둔다
    val accessTokenValidity: Duration = Duration.ofMinutes(15),
    /**
     * 단일 기기 로컬 앱이라 길게 둔다. 개별 토큰 폐기 수단이 없어 유출 시 ORBIT_JWT_SECRET 교체뿐이다.
     * 다중 사용자나 외부 접근을 허용하면 refresh 회전·jti 폐기를 넣고 이 값을 줄여야 한다.
     */
    val refreshTokenValidity: Duration = Duration.ofDays(365),
) {
    init {
        // HS256 키가 256비트 미만이면 약한 토큰을 발급하느니 기동에 실패한다
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "orbit.jwt.secret 은 최소 32바이트여야 합니다 (HS256 요구사항)"
        }
    }
}

/**
 * jjwt 0.12 의 `verifyWith(key)` 는 알고리즘을 키에 고정해 alg=none·alg 혼동 공격을 막는다.
 * 테스트에서 만료를 재현할 수 있도록 주입된 [Clock] 으로 만료를 판정한다.
 */
@Component
class JwtTokenProvider(
    private val properties: JwtProperties,
    private val clock: Clock,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray(Charsets.UTF_8))

    fun issueAccessToken(userId: Long, email: String): String =
        issue(userId, email, TokenType.ACCESS, properties.accessTokenValidity)

    fun issueRefreshToken(userId: Long, email: String): String =
        issue(userId, email, TokenType.REFRESH, properties.refreshTokenValidity)

    val accessTokenValiditySeconds: Long get() = properties.accessTokenValidity.seconds

    private fun issue(userId: Long, email: String, type: TokenType, validity: Duration): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .claim("token_type", type.claimValue)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now + validity))
            .signWith(key)
            .compact()
    }

    /** 서명·만료·토큰 종류를 검증한다. 실패 사유는 구분하지 않고 같은 예외로 던진다. */
    fun parse(token: String, expected: TokenType): AuthenticatedUser {
        val claims = try {
            Jwts.parser()
                .verifyWith(key)
                .clock { Date.from(clock.instant()) }
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: JwtException) {
            throw InvalidTokenException("유효하지 않은 토큰입니다")
        } catch (e: IllegalArgumentException) {
            throw InvalidTokenException("유효하지 않은 토큰입니다")
        }

        if (TokenType.from(claims["token_type"] as? String) != expected) {
            throw InvalidTokenException("유효하지 않은 토큰입니다")
        }
        return AuthenticatedUser(id = subjectId(claims), email = claims["email"] as? String ?: "")
    }

    private fun subjectId(claims: Claims): Long =
        claims.subject?.toLongOrNull() ?: throw InvalidTokenException("유효하지 않은 토큰입니다")
}

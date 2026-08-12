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
 * 토큰 종류. 액세스 토큰으로 재발급을 받거나, 리프레시 토큰으로 API 를 호출하는
 * 혼용을 막기 위해 클레임에 박아 넣고 검증한다.
 *
 * 클레임 이름을 `typ` 이 아니라 `token_type` 으로 쓴 이유: `typ` 은 JOSE 헤더의
 * 표준 파라미터(보통 "JWT")라, 같은 이름을 페이로드에서 다른 뜻으로 쓰면 헷갈린다.
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
    /**
     * 액세스 토큰은 무상태로 검증한다(요청마다 DB 조회 없음). 그 대가로 탈퇴·차단이
     * 즉시 반영되지 않으므로, 반영 지연의 상한이 곧 이 값이다. 그래서 짧게 잡는다.
     */
    val accessTokenValidity: Duration = Duration.ofMinutes(15),
    val refreshTokenValidity: Duration = Duration.ofDays(14),
) {
    init {
        // HS256 은 키가 256비트 미만이면 안전하지 않다. 잘못된 설정으로 뜬 서버가
        // 조용히 약한 토큰을 발급하는 것보다, 기동에 실패하는 편이 낫다.
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "orbit.jwt.secret 은 최소 32바이트여야 합니다 (HS256 요구사항)"
        }
    }
}

/**
 * JWT 발급·검증.
 *
 * 라이브러리로 jjwt 를 골랐다. nimbus-jose-jwt 는 JWE·JWK 세트·OIDC 까지 다루는
 * 범용 구현이라 지금 필요한 것(대칭키 HS256 서명 하나)에 비해 표면이 넓고,
 * jjwt 0.12 는 `verifyWith(key)` 로 파싱 시 알고리즘이 키에 고정되어
 * alg=none / alg 혼동(HS256 ↔ RS256) 공격이 API 수준에서 막힌다.
 *
 * 만료 판정에 시스템 시각 대신 주입된 [Clock] 을 쓴다. 테스트에서 "만료된 토큰"을
 * 실제로 기다리지 않고 만들 수 있어야 하기 때문이다.
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

    /**
     * 서명·만료·토큰 종류를 모두 검증한 뒤 주체를 돌려준다.
     * 실패 사유(만료/서명 불일치/종류 불일치)는 호출자에게 구분해서 알리지 않는다 —
     * 공격자에게 어느 단계까지 통과했는지 알려줄 이유가 없다.
     */
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

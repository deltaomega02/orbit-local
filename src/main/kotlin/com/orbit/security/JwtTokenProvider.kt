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
    /**
     * 365일. 원래는 14일이었다.
     *
     * **왜 늘렸나.** 이 앱은 옷을 등록하고 오늘 뭘 입을지 보는, 하루 한 번 여는
     * 개인용 로컬 앱이고 로그아웃 버튼도 없앤다. 리프레시가 14일이면 2주에 한 번은
     * 아무 이유 없이 로그인 화면을 만나게 되는데, 그건 보안이 아니라 마찰이다.
     * 강제 로그아웃 수단이 없는 구조(회전도 폐기 저장소도 없다)에서 짧은 만료는
     * "공격자를 끊는 장치"가 아니라 "사용자만 끊는 장치"로 작동한다 — 공격자는
     * 훔친 순간부터 14일을 마음껏 쓰고, 정작 사용자만 14일마다 비밀번호를 다시 친다.
     *
     * **대가.** 리프레시 토큰이 새면 노출 기간이 최대 1년이다. 서버가 특정 토큰만
     * 무효화할 방법은 여전히 없고, 사고가 났을 때 쓸 수 있는 유일한 수단은
     * `ORBIT_JWT_SECRET` 을 바꿔 **모든** 사용자의 토큰을 한꺼번에 죽이는 것뿐이다.
     * 14일일 때는 "가만히 두면 2주 뒤 알아서 끊긴다"가 최후의 안전망이었는데,
     * 그 안전망을 1년으로 늘린 것이 이 변경의 실체다.
     *
     * **그래도 받아들이는 이유.** (1) 담긴 데이터가 옷 사진과 코디 기록이고,
     * (2) 사용자가 자기 기기 한 대에서 쓰는 로컬 앱이며, (3) 토큰이 그 기기의
     * 브라우저 저장소 밖으로 나가지 않는다. 토큰이 샜다는 건 이미 그 기기가
     * 털렸다는 뜻이고, 그 상황에서 1년이냐 14일이냐는 큰 차이를 만들지 않는다.
     *
     * **액세스 토큰 15분은 그대로 둔다.** 매 요청에 실려 다녀 노출 빈도가 훨씬 높고,
     * 무상태 검증이라 탈퇴·차단 반영 지연의 상한이기도 하다. 늘려서 얻는 편의
     * (재발급 호출 감소)는 사용자가 체감하지도 못한다 — 재발급은 화면 뒤에서
     * 자동으로 일어나기 때문이다.
     *
     * 여러 사람이 쓰거나 기기 밖에서 접근하게 되는 순간 이 판단은 무효다.
     * 그때는 리프레시 회전 + jti 폐기 저장소를 먼저 넣고 이 값을 다시 줄여야 한다.
     */
    val refreshTokenValidity: Duration = Duration.ofDays(365),
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

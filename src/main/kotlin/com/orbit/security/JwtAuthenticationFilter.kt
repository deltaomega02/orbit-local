package com.orbit.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** 컨트롤러가 `@AuthenticationPrincipal` 로 받는 주체. 토큰에서 나온 값만 담는다. */
data class AuthenticatedUser(val id: Long, val email: String)

private const val BEARER_PREFIX = "Bearer "

/**
 * 요청 1회당 한 번만 도는 토큰 검증 필터.
 *
 * **이 파일이 이 저장소의 핵심이다.** 원본 Django 에서는 아래 4줄이 11개 뷰에
 * 복붙돼 있었다.
 *
 * ```python
 * token = request.headers.get("Authorization", "").replace("Token ", "")
 * user = User.objects.filter(email=token).first()
 * if not user:
 *     return Response(status=401)
 * ```
 *
 * 복붙이라 한 군데만 빠지면 그 엔드포인트는 무방비였고, 실제로 토큰이 이메일
 * 그 자체라 서명도 만료도 없었다. 여기서는 검증이 필터 하나에 모이고, 컨트롤러는
 * 인증 코드를 한 줄도 갖지 않는다.
 *
 * 토큰이 없거나 깨졌으면 여기서 401 을 쓰지 않고 그냥 인증 없이 통과시킨다.
 * 접근 거부 판단은 [org.springframework.security.web.access.intercept.AuthorizationFilter]
 * 의 몫이다 — 인증 정보 없이도 열려 있는 경로(회원가입 등)를 필터가 미리 막으면 안 된다.
 */
@Component
class JwtAuthenticationFilter(
    private val tokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        resolveToken(request)?.let { token ->
            try {
                val user = tokenProvider.parse(token, TokenType.ACCESS)
                val authentication = UsernamePasswordAuthenticationToken(
                    user,
                    null, // 자격증명은 검증이 끝난 뒤 컨텍스트에 남길 이유가 없다
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                )
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: InvalidTokenException) {
                // 컨텍스트를 비운 채로 진행 → 보호된 경로면 EntryPoint 가 401 을 낸다
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? =
        request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * 인증/인가 실패 응답도 `{"error": ...}` 모양으로 통일한다. 시큐리티 필터에서 난
 * 예외는 `@RestControllerAdvice` 가 잡지 못하므로 여기서 직접 쓴다 —
 * 이걸 두지 않으면 401 만 서블릿 기본 HTML 로 나가서 클라이언트가 파싱에 실패한다.
 */
@Component
class JsonAuthenticationErrorHandler(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: org.springframework.security.core.AuthenticationException,
    ) = write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "인증이 필요합니다")

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: org.springframework.security.access.AccessDeniedException,
    ) = write(response, HttpStatus.FORBIDDEN, "forbidden", "권한이 없습니다")

    private fun write(response: HttpServletResponse, status: HttpStatus, error: String, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, mapOf("error" to error, "detail" to detail))
    }
}

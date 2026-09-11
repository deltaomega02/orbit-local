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
 * 토큰이 없거나 잘못됐으면 401 을 쓰지 않고 인증 없이 통과시킨다.
 * 공개 경로가 있으므로 접근 거부는 AuthorizationFilter 가 판단한다.
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
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                )
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: InvalidTokenException) {
                // 보호된 경로면 EntryPoint 가 401 을 낸다
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
 * 시큐리티 필터 예외는 `@RestControllerAdvice` 가 잡지 못하므로 여기서 JSON 을 직접 쓴다.
 * 401 `unauthorized`, 403 `forbidden`. `detail` 은 ApiExceptionHandler 와 같이 일본어.
 */
@Component
class JsonAuthenticationErrorHandler(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: org.springframework.security.core.AuthenticationException,
    ) = write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "認証が必要です")

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: org.springframework.security.access.AccessDeniedException,
    ) = write(response, HttpStatus.FORBIDDEN, "forbidden", "権限がありません")

    private fun write(response: HttpServletResponse, status: HttpStatus, error: String, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, mapOf("error" to error, "detail" to detail))
    }
}

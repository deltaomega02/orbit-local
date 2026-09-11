package com.orbit.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import jakarta.servlet.DispatcherType
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val errorHandler: JsonAuthenticationErrorHandler,
) {

    /** 기본은 인증 필요이고 공개 경로만 나열한다. */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        // 토큰을 헤더로 받고 쿠키/세션을 쓰지 않으므로 CSRF 대상이 아니다
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .authorizeHttpRequests {
            // ERROR 디스패치에서는 JWT 필터가 돌지 않아, 막으면 400·500 이 /error 에서 401 로 바뀐다
            it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // /api/auth/session 은 자격증명 없이 토큰을 발급한다.
                // 서버가 루프백에만 뜬다는 전제(application.yml server.address)에 기댄다.
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/auth/session",
                    "/api/auth/signup",
                    "/api/auth/login",
                    "/api/auth/refresh",
                )
                .permitAll()
                // 자동 세션을 부르려면 정적 화면은 인증 없이 떠야 한다.
                // 새 경로가 실수로 열리지 않도록 파일을 하나씩 나열한다.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/api.js", "/style.css", "/favicon.ico")
                .permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler)
        }
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}

package com.orbit.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

    /**
     * 기본값이 "인증 필요"이고, 열어주는 경로만 나열한다.
     * 반대로(기본 허용 + 막을 경로 나열) 짜면 엔드포인트를 추가할 때마다
     * 규칙을 갱신해야 하고, 잊으면 그대로 구멍이 된다. 원본에서 실제로 그랬다.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        // 토큰을 헤더로 받고 쿠키/세션을 쓰지 않으므로 CSRF 공격 경로 자체가 없다.
        // (브라우저가 자동으로 붙여주는 자격증명이 없으면 위조 요청이 인증되지 않는다)
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh")
                .permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler)
        }
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()

    /**
     * BCrypt. 해시마다 salt 가 내장되고 cost 를 올릴 수 있어, 하드웨어가 빨라져도
     * 설정 한 줄로 따라갈 수 있다. 기본 강도 10 은 대략 50~100ms 수준이라
     * 로그인 지연과 대입 공격 비용 사이에서 무난하다.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}

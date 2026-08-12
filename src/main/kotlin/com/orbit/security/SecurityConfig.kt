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
            // 에러 디스패치를 인증에서 빼둔다. 이걸 막아두면 400·500 처럼 이미 발생한
            // 오류가 /error 로 넘어가면서 전부 401 로 바뀌어 나간다. 필터는 기본적으로
            // ERROR 디스패치에서 돌지 않으므로 인증 정보가 없는 상태로 도착하기 때문이다.
            // 사용자는 "키가 틀렸다" 대신 "로그인이 필요하다"를 보게 되고, 진짜 원인은
            // 사라진다.
            it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh")
                .permitAll()
                // 화면 껍데기(HTML/CSS/JS)는 인증 없이 내려준다. 로그인 화면을 보려면
                // 먼저 화면이 떠야 하는데, 여기까지 막으면 로그인 자체가 불가능해진다.
                // 사용자 데이터는 전부 /api/** 와 /media/** 뒤에 있으므로 이걸 열어도
                // 노출되는 것은 없다. 경로를 하나씩 나열해 새 경로가 실수로 열리지 않게 한다.
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/api.js", "/style.css", "/favicon.ico")
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

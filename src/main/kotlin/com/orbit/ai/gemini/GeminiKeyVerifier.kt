package com.orbit.ai.gemini

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

/**
 * 저장 전에 키가 실제로 유효한지 확인한다. 토큰을 쓰지 않는 모델 목록 조회를 한 번 호출한다.
 * 사용자가 저장 버튼을 누르고 기다리는 요청이라 타임아웃을 짧게 둔다.
 */
class GeminiKeyVerifier(
    private val properties: GeminiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(10))
            },
        )
        .build()

    /**
     * VALID: 키가 통함, INVALID: 4xx 로 거절됨(저장 불가), UNVERIFIED: 확인 못 함(오프라인·타임아웃·5xx).
     * 오프라인에서도 키를 저장할 수 있도록 UNVERIFIED 는 실패로 취급하지 않는다.
     */
    enum class Result { VALID, INVALID, UNVERIFIED }

    fun verify(apiKey: String): Result {
        if (properties.enabled.not()) {
            // AI 를 꺼둔 환경(테스트 포함)에서는 네트워크를 타지 않는다.
            return Result.UNVERIFIED
        }
        return try {
            restClient.get()
                .uri("/models?pageSize=1")
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .toBodilessEntity()
            Result.VALID
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            // 429 는 키 오류가 아니므로 UNVERIFIED
            if (e.statusCode.value() == 429) Result.UNVERIFIED else Result.INVALID
        } catch (e: RestClientException) {
            log.warn("Gemini 키 검증을 완료하지 못했다: {}", e.javaClass.simpleName)
            Result.UNVERIFIED
        }
    }
}

// 설정 화면이 의존하므로 `orbit.gemini.enabled=false` 여도 검증기는 항상 등록한다.
@Configuration
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiSettingsConfig {

    @Bean
    fun geminiKeyVerifier(properties: GeminiProperties) = GeminiKeyVerifier(properties)
}

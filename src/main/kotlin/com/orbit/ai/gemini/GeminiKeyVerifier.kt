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
 * 저장하기 전에 키가 실제로 쓸 수 있는 값인지 한 번 확인한다.
 *
 * 형식만 보고 통과시키면 오타 하나에 "AI 기능이 안 돼요"만 남고 원인을 알 방법이
 * 없다. 사용자는 서버 로그를 볼 수 없는 사람이다. 그래서 가장 싼 호출인 모델 목록
 * 조회를 한 번 때려 본다 — 토큰을 소비하지 않고, 키가 유효한지/권한이 있는지를
 * 그대로 알려준다.
 *
 * 타임아웃을 짧게 잡은 이유: 이건 사용자가 "저장"을 누르고 기다리는 화면이다.
 * 네트워크가 느릴 때 60초를 붙잡고 있으면 앱이 멈춘 것처럼 보인다.
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
     * 결과는 세 가지다.
     *  - [Result.VALID]        키가 통했다
     *  - [Result.INVALID]      상대가 4xx 로 거절했다. 저장하면 안 된다
     *  - [Result.UNVERIFIED]   확인 자체를 못 했다(오프라인·타임아웃·5xx)
     *
     * 세 번째를 실패로 묶지 않는 것이 중요하다. 비행기 안에서 키를 넣는 사용자에게
     * "인터넷이 없으니 키를 저장할 수 없습니다"라고 하는 건 과하다. 못 물어본 것과
     * 물어봤더니 아니라는 것은 다른 사실이다.
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
            // 429(레이트 리밋)는 "키가 틀렸다"가 아니라 "지금 물어볼 수 없다"이다.
            if (e.statusCode.value() == 429) Result.UNVERIFIED else Result.INVALID
        } catch (e: RestClientException) {
            log.warn("Gemini 키 검증을 완료하지 못했다: {}", e.javaClass.simpleName)
            Result.UNVERIFIED
        }
    }
}

/**
 * 검증기는 AI 어댑터와 달리 **항상** 등록한다. `orbit.gemini.enabled=false` 여도
 * 설정 화면은 떠야 하고, 그 화면이 의존하는 빈이 없으면 컨텍스트가 뜨지 않는다.
 * (호출 자체는 위에서 enabled 를 보고 막는다)
 */
@Configuration
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiSettingsConfig {

    @Bean
    fun geminiKeyVerifier(properties: GeminiProperties) = GeminiKeyVerifier(properties)
}

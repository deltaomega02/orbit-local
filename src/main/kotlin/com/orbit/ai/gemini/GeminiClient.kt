package com.orbit.ai.gemini

import com.fasterxml.jackson.databind.JsonNode
import com.orbit.ai.AiCallFailedException
import com.orbit.ai.AiUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties("orbit.gemini")
data class GeminiProperties(
    /** 환경변수 GEMINI_API_KEY 로만 받는다. 설정 파일 기본값은 빈 문자열. */
    val apiKey: String = "",
    val enabled: Boolean = true,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val textModel: String = "gemini-3.6-flash",
    val imageModel: String = "gemini-3.1-flash-image",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** 이미지 생성은 수 초가 걸린다. */
    val readTimeout: Duration = Duration.ofSeconds(60),
    val maxAttempts: Int = 3,
    val retryBackoff: Duration = Duration.ofMillis(500),
)

/**
 * Gemini generateContent 호출.
 * 타임아웃·네트워크 오류·5xx·429 만 재시도하고, 그 외 4xx 는 결과가 같으므로 바로 실패한다.
 */
class GeminiClient(
    private val properties: GeminiProperties,
    private val keyStore: GeminiKeyStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.connectTimeout)
                setReadTimeout(properties.readTimeout)
            },
        )
        .build()

    /**
     * 키가 없으면 호출하지 않고 503 을 던진다.
     * 설정 화면에서 등록한 키가 재시작 없이 반영되도록 호출 시점에 [GeminiKeyStore] 에서 읽는다.
     */
    fun requireUsable() {
        if (keyStore.current().isBlank()) {
            throw AiUnavailableException("Gemini API 키가 등록되지 않았습니다")
        }
    }

    fun generateContent(model: String, body: Map<String, Any?>): JsonNode {
        requireUsable()
        var lastFailure: Exception? = null

        repeat(properties.maxAttempts) { attempt ->
            try {
                return restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    // 키를 쿼리 스트링이 아니라 헤더로 보낸다. URL 은 프록시·액세스 로그에 그대로 남는다.
                    .header("x-goog-api-key", keyStore.current())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
                    ?: throw AiCallFailedException("Gemini 응답 본문이 비어 있습니다")
            } catch (e: HttpClientErrorException) {
                if (e.statusCode.value() != 429) {
                    throw AiCallFailedException("Gemini 요청이 거부되었습니다 (${e.statusCode})", e)
                }
                lastFailure = e
            } catch (e: HttpServerErrorException) {
                lastFailure = e
            } catch (e: ResourceAccessException) {
                lastFailure = e
            }

            if (attempt < properties.maxAttempts - 1) {
                // 지수 백오프
                val wait = properties.retryBackoff.toMillis() shl attempt
                log.warn("Gemini 호출 실패, {}ms 뒤 재시도 ({}/{})", wait, attempt + 1, properties.maxAttempts)
                Thread.sleep(wait)
            }
        }
        throw AiCallFailedException("Gemini 호출이 ${properties.maxAttempts}회 모두 실패했습니다", lastFailure)
    }
}

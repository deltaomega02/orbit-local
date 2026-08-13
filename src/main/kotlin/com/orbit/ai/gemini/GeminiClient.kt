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
    /**
     * 키는 환경변수 GEMINI_API_KEY 로만 들어온다. 설정 파일에는 기본값이 빈 문자열이라
     * 실수로 커밋될 실제 키 자체가 존재하지 않는다.
     */
    val apiKey: String = "",
    val enabled: Boolean = true,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val textModel: String = "gemini-3.6-flash",
    val imageModel: String = "gemini-3.1-flash-image",
    /** 연결은 빨리 포기한다 — 붙지도 않는 상대를 오래 기다릴 이유가 없다. */
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** 생성은 원래 느리다. 특히 이미지 생성은 수 초가 걸린다. */
    val readTimeout: Duration = Duration.ofSeconds(60),
    val maxAttempts: Int = 3,
    val retryBackoff: Duration = Duration.ofMillis(500),
)

/**
 * Gemini generateContent 호출 한 지점.
 *
 * 재시도 정책이 이 클래스의 핵심이다. 무조건 3번 때리는 재시도는 실패를 3배로
 * 비싸게 만들 뿐이다. 그래서 다시 걸어볼 의미가 있는 것만 다시 건다.
 *  - **재시도한다**: 타임아웃/네트워크 오류, 5xx, 429(레이트 리밋). 상대편 사정이라
 *    잠시 뒤면 성공할 수 있다.
 *  - **재시도하지 않는다**: 그 외 4xx. 잘못된 파라미터·잘못된 키·안전 필터 거부는
 *    같은 요청을 다시 보내도 같은 답이 온다. 반복해봐야 쿼터만 태운다.
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
     * 키가 없으면 호출 자체를 시도하지 않는다. AI 엔드포인트만 503 이 되고 나머지는 그대로 산다.
     *
     * 키는 **호출 시점에** [GeminiKeyStore] 에서 읽는다. 기동 시점의 설정값을 붙들고 있으면,
     * 사용자가 앱 안에서 키를 등록해도 재시작 전까지 계속 503 이 나간다. 실제로 그랬다.
     * 이 앱은 받는 사람이 터미널을 쓰지 않는 것을 전제로 하므로, 키가 들어오는 정상 경로가
     * 곧 설정 화면이다. 그 경로가 즉시 반영되지 않으면 기능이 없는 것과 같다.
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
                    // 재시도해도 결과가 같은 오류. 여기서 즉시 포기한다.
                    throw AiCallFailedException("Gemini 요청이 거부되었습니다 (${e.statusCode})", e)
                }
                lastFailure = e
            } catch (e: HttpServerErrorException) {
                lastFailure = e
            } catch (e: ResourceAccessException) {
                lastFailure = e
            }

            if (attempt < properties.maxAttempts - 1) {
                // 지수 백오프. 상대가 밀려 있을 때 같은 간격으로 몰아치면 상황을 더 나쁘게 만든다.
                val wait = properties.retryBackoff.toMillis() shl attempt
                log.warn("Gemini 호출 실패, {}ms 뒤 재시도 ({}/{})", wait, attempt + 1, properties.maxAttempts)
                Thread.sleep(wait)
            }
        }
        throw AiCallFailedException("Gemini 호출이 ${properties.maxAttempts}회 모두 실패했습니다", lastFailure)
    }
}

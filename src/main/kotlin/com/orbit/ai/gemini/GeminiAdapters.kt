package com.orbit.ai.gemini

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.AiCallFailedException
import com.orbit.ai.ClothingAnalysis
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.OutfitSuggestion
import com.orbit.ai.RecommendRequest
import com.orbit.ai.TryOnImageGenerator
import com.orbit.domain.MainCategory
import com.orbit.media.ImageType
import org.slf4j.LoggerFactory
import java.util.Base64

private val BASE64 = Base64.getEncoder()
private val BASE64_DECODER = Base64.getMimeDecoder()

/** `contents` 한 덩어리를 만드는 헬퍼. 텍스트와 이미지 파트를 섞을 수 있다. */
private fun userContent(vararg parts: Map<String, Any?>): List<Map<String, Any?>> =
    listOf(mapOf("role" to "user", "parts" to parts.toList()))

private fun textPart(text: String): Map<String, Any?> = mapOf("text" to text)

private fun imagePart(bytes: ByteArray, mime: String): Map<String, Any?> =
    mapOf("inlineData" to mapOf("mimeType" to mime, "data" to BASE64.encodeToString(bytes)))

/** 저장된 바이트에서 실제 형식을 다시 확인한다 — 업로드 때 검증한 값과 같은 근거를 쓴다. */
private fun imagePart(bytes: ByteArray): Map<String, Any?> =
    imagePart(bytes, ImageType.detect(bytes)?.mime ?: "image/jpeg")

/** 응답에서 텍스트 파트를 모두 이어 붙인다. 모델이 파트를 쪼개 보낼 수 있다. */
private fun JsonNode.joinedText(): String =
    path("candidates").firstOrNull()?.path("content")?.path("parts")
        ?.mapNotNull { it.path("text").takeIf { t -> t.isTextual }?.asText() }
        ?.joinToString("")
        .orEmpty()

private fun JsonNode.firstInlineImage(): ByteArray? =
    path("candidates").firstOrNull()?.path("content")?.path("parts")
        ?.firstOrNull { it.path("inlineData").path("data").isTextual }
        ?.path("inlineData")?.path("data")?.asText()
        ?.let { BASE64_DECODER.decode(it) }

/**
 * 사진 한 장으로 옷 정보를 추정한다.
 *
 * 실패해도 예외 대신 같은 shape 의 기본값을 돌려준다. 이 기능은 "등록 폼을 미리
 * 채워주는" 편의 기능이라, AI 가 헛디뎠다고 등록 자체를 막을 이유가 없다.
 * 사용자는 어차피 저장 전에 값을 고칠 수 있다.
 */
class GeminiClothingAnalyzer(
    private val client: GeminiClient,
    private val properties: GeminiProperties,
    private val objectMapper: ObjectMapper,
) : ClothingAnalyzer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun analyze(image: ByteArray, mime: String): ClothingAnalysis {
        val response = client.generateContent(
            properties.textModel,
            mapOf(
                "contents" to userContent(
                    textPart(
                        """
                        이 사진 속 옷 한 벌을 한국어로 설명해라.
                        name 은 20자 이내의 짧은 이름, mainCategory 는 TOP/BOTTOM/OUTER 중 하나,
                        color 는 대표 색 한 단어, detail 은 소재·핏·어울리는 상황을 한 문장으로.
                        """.trimIndent(),
                    ),
                    imagePart(image, mime),
                ),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json",
                    "responseSchema" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "name" to mapOf("type" to "STRING"),
                            "mainCategory" to mapOf("type" to "STRING", "enum" to MainCategory.entries.map { it.name }),
                            "color" to mapOf("type" to "STRING"),
                            "detail" to mapOf("type" to "STRING"),
                        ),
                        "required" to listOf("name", "mainCategory"),
                    ),
                ),
            ),
        )

        return parseOrFallback(response.joinedText())
    }

    /**
     * 파싱을 따로 뺀 이유는 테스트 때문이다. "모델이 JSON 이 아닌 걸 돌려줬을 때"를
     * 검증하려면 이 함수만 부르면 되고, 그 확인에 네트워크가 필요할 이유가 없다.
     */
    internal fun parseOrFallback(rawText: String): ClothingAnalysis = runCatching {
        val json = objectMapper.readTree(rawText)
        ClothingAnalysis(
            name = json.path("name").asText("").ifBlank { "새 옷" },
            mainCategory = MainCategory.entries
                .firstOrNull { it.name == json.path("mainCategory").asText("") }
                ?: MainCategory.TOP,
            color = json.path("color").asText("").ifBlank { null },
            detail = json.path("detail").asText("").ifBlank { null },
        )
    }.getOrElse {
        log.warn("의류 분석 응답 파싱 실패, 기본값으로 대체한다", it)
        FALLBACK
    }

    internal companion object {
        val FALLBACK = ClothingAnalysis("새 옷", MainCategory.TOP, null, "AI 가 사진을 해석하지 못했습니다. 직접 입력해 주세요.")
    }
}

/**
 * 옷장에서 오늘의 조합을 고른다.
 *
 * AI 에게 자유 텍스트로 "흰 셔츠랑 청바지"라고 말하게 두면, 그 문자열을 다시 옷장과
 * 맞춰보는 애매한 매칭 코드가 필요해지고 결국 틀린다. 그래서 responseSchema 로
 * **후보 id 의 배열**만 받도록 강제한다. 그래도 모델은 없는 id 를 지어낼 수 있으므로,
 * 실제 소유 여부 검증은 서버가 별도로 한다([com.orbit.service.OutfitAiService]).
 *
 * 파싱에 실패해도 같은 shape 를 돌려준다. 호출부가 "성공 응답"과 "폴백"을 구분해
 * 분기하지 않아도 되게 하기 위해서다.
 */
class GeminiOutfitRecommender(
    private val client: GeminiClient,
    private val properties: GeminiProperties,
    private val objectMapper: ObjectMapper,
) : OutfitRecommender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun recommend(req: RecommendRequest): OutfitSuggestion {
        val response = client.generateContent(
            properties.textModel,
            mapOf(
                "contents" to userContent(textPart(buildPrompt(req))),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json",
                    "responseSchema" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "title" to mapOf("type" to "STRING"),
                            "reason" to mapOf("type" to "STRING"),
                            "clothesIds" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "INTEGER")),
                        ),
                        "required" to listOf("title", "reason", "clothesIds"),
                    ),
                ),
            ),
        )

        return parseOrFallback(response.joinedText(), req)
    }

    /**
     * 응답 텍스트를 [OutfitSuggestion] 으로 바꾼다. 실패하면 **같은 shape 의** 폴백을
     * 돌려준다. 호출부가 "성공 응답"과 "폴백"을 구분해 분기하지 않아도 되게 하려는
     * 것이고, 덕분에 예외 경로가 하나 줄어든다.
     */
    internal fun parseOrFallback(rawText: String, req: RecommendRequest): OutfitSuggestion = runCatching {
        val json = objectMapper.readTree(rawText)
        val ids = json.path("clothesIds").mapNotNull { it.takeIf { n -> n.isNumber }?.asLong() }
        check(ids.isNotEmpty()) { "clothesIds 가 비어 있다" }
        OutfitSuggestion(
            title = json.path("title").asText("").ifBlank { "오늘의 코디" },
            reason = json.path("reason").asText("").ifBlank { "무난하게 어울리는 조합입니다." },
            clothesIds = ids,
        )
    }.getOrElse {
        log.warn("추천 응답 파싱 실패, 규칙 기반 조합으로 대체한다", it)
        fallback(req)
    }

    private fun buildPrompt(req: RecommendRequest): String = buildString {
        appendLine("너는 옷장 코디네이터다. 아래 후보에서 상의 1벌과 하의 1벌을 반드시 고르고, 어울리면 아우터 1벌을 더해라.")
        appendLine("후보:")
        req.candidates.forEach {
            appendLine("- id=${it.id} / 이름=${it.name} / 분류=${it.mainCategory} / 색=${it.color ?: "미상"}")
        }
        if (req.avoidCombinations.isNotEmpty()) {
            appendLine()
            appendLine("아래 조합은 오늘 이미 추천했다. 같은 조합은 절대 다시 고르지 마라.")
            req.avoidCombinations.forEach { appendLine("- ${it.sorted()}") }
        }
        appendLine()
        appendLine("clothesIds 에는 위 후보에 실제로 있는 id 만 넣어라. 새 id 를 만들어내지 마라.")
        appendLine("reason 은 왜 이 조합인지 한국어 두 문장 이내로 써라.")
    }

    /** 응답을 못 읽었을 때의 결정적 대체안. 상의·하의를 하나씩 집는다. */
    private fun fallback(req: RecommendRequest): OutfitSuggestion {
        val picked = listOfNotNull(
            req.candidates.firstOrNull { it.mainCategory == MainCategory.TOP },
            req.candidates.firstOrNull { it.mainCategory == MainCategory.BOTTOM },
        )
        return OutfitSuggestion(
            title = "오늘의 코디",
            reason = "AI 응답을 해석하지 못해 기본 조합을 제안합니다.",
            clothesIds = picked.map { it.id },
        )
    }
}

/**
 * 전신 사진에 옷을 합성한다.
 *
 * 여기만은 폴백이 없다. "옷을 입은 내 사진"은 만들지 못하면 만들지 못한 것이고,
 * 그럴듯한 대체 이미지를 돌려주면 사용자를 속이는 셈이 된다.
 */
class GeminiTryOnImageGenerator(
    private val client: GeminiClient,
    private val properties: GeminiProperties,
) : TryOnImageGenerator {

    override fun generate(bodyPhoto: ByteArray, items: List<ByteArray>): ByteArray {
        val parts = buildList {
            add(
                textPart(
                    """
                    첫 번째 사진은 사람의 전신 사진이다. 나머지 사진들은 옷이다.
                    이 사람이 그 옷들을 실제로 입은 모습을 한 장의 사진으로 만들어라.
                    얼굴·체형·배경은 그대로 유지하고 옷만 바꿔라.
                    """.trimIndent(),
                ),
            )
            add(imagePart(bodyPhoto))
            items.forEach { add(imagePart(it)) }
        }

        val response = client.generateContent(
            properties.imageModel,
            mapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
                // 이미지 전용 모델이라도 응답 모달리티를 명시해 두는 편이 안전하다.
                "generationConfig" to mapOf("responseModalities" to listOf("IMAGE")),
            ),
        )

        return response.firstInlineImage()
            ?: throw AiCallFailedException("Gemini 응답에 이미지가 없습니다")
    }
}

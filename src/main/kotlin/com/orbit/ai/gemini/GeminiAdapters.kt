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
                        너는 의류 카탈로그를 만드는 편집자다. 사진 속 옷 한 벌을 한국어로 기록해라.

                        [무엇을 볼 것인가]
                        - 사진에 옷이 여러 벌 보이면 **화면에서 가장 크고 중심에 있는 한 벌**만 대상으로 한다.
                        - 사람이 입고 있어도 사람이 아니라 옷을 기록한다. 얼굴·배경·소품은 무시한다.

                        [name] 20자 이내. "색 + 특징 + 종류" 순서로 쓴다.
                          좋은 예: "네이비 옥스퍼드 셔츠", "연청 와이드 데님"
                          나쁜 예: "셔츠", "예쁜 옷", "상의 1"

                        [mainCategory] 다음 기준으로만 정한다. 애매하면 규칙을 우선한다.
                          TOP    — 티셔츠·셔츠·블라우스·니트·후드티·맨투맨 (단독으로 입는 상의)
                          BOTTOM — 바지·청바지·슬랙스·치마·반바지
                          OUTER  — 자켓·코트·패딩·가디건·집업후디 (상의 위에 겹쳐 입는 것)
                          * 앞이 완전히 열리는(지퍼·단추) 겉옷은 OUTER 다.
                          * 원피스처럼 상하의가 붙은 옷은 TOP 으로 한다.

                        [color] 한국어 대표색 **한 단어**. 무늬가 있으면 바탕의 주된 색을 쓴다.
                          허용 예: 화이트 블랙 그레이 네이비 베이지 브라운 카키 아이보리 연청 진청 레드 핑크 그린 블루

                        [detail] 한 문장, 60자 이내. **추천에 실제로 쓰일 정보만** 담는다 —
                          소재감(면·니트·데님·리넨·기모), 핏(오버·슬림·와이드), 두께,
                          어울리는 계절과 상황. 감상이나 칭찬은 쓰지 마라.
                          좋은 예: "도톰한 기모 소재로 겨울 캐주얼에 적합한 오버핏"
                          나쁜 예: "정말 예쁘고 스타일리시한 옷입니다"

                        확신이 없어도 비워두지 말고 사진에서 보이는 근거로 가장 그럴듯한 값을 골라라.
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
        appendLine("너는 개인 옷장을 다루는 스타일리스트다. 아래 옷장에서 오늘 입을 한 벌을 고른다.")
        appendLine()
        appendLine("[내 옷장]")
        req.candidates.groupBy { it.mainCategory }.forEach { (category, items) ->
            appendLine("$category:")
            items.forEach {
                val parts = listOfNotNull(
                    "id=${it.id}",
                    it.name,
                    it.color?.let { c -> "색:$c" },
                    it.detail?.takeIf { d -> d.isNotBlank() },
                )
                appendLine("  - ${parts.joinToString(" / ")}")
            }
        }
        appendLine()
        appendLine("[고르는 규칙]")
        appendLine("1. 상의(TOP) 1벌과 하의(BOTTOM) 1벌은 반드시 고른다. 아우터(OUTER)는 어울릴 때만 1벌 더한다.")
        appendLine("2. 색은 전체 3색 이내로 맞춘다. 톤을 통일하거나, 무채색 바탕에 한 곳만 색을 준다.")
        appendLine("3. 소재와 두께의 계절감을 맞춘다. 두꺼운 니트에 얇은 리넨 하의처럼 계절이 어긋나는 조합은 피한다.")
        appendLine("4. 핏의 균형을 본다. 상의가 오버핏이면 하의는 정리된 실루엣으로 두는 식이다.")
        appendLine("5. 옷장에 있는 것만 쓴다. **id 를 새로 만들어내지 마라.** 없는 id 를 넣으면 이 응답은 폐기된다.")
        if (req.avoidCombinations.isNotEmpty()) {
            appendLine()
            appendLine("[오늘 이미 나온 조합 — 같은 구성을 다시 고르지 마라]")
            req.avoidCombinations.forEach { appendLine("  - ${it.sorted().joinToString(", ")}") }
            appendLine("위와 **한 벌이라도 다른** 조합을 만들어라. 정말 다른 조합이 없다면 가장 덜 비슷한 것을 고른다.")
        }
        appendLine()
        appendLine("[출력]")
        appendLine("title  — 오늘의 분위기가 드러나는 12자 이내 이름. 예: \"단정한 출근룩\", \"편한 주말 산책\"")
        appendLine("reason — 왜 이 조합인지 한국어 두 문장 이내.")
        appendLine("         **색·소재·핏 중 실제로 근거가 된 것을 짚어서** 쓴다.")
        appendLine("         좋은 예: \"네이비 셔츠의 차분한 톤에 연청 데님으로 가볍게 풀었어요. 도톰한 소재라 아침저녁 쌀쌀할 때 좋아요.\"")
        appendLine("         나쁜 예: \"잘 어울리는 조합입니다.\"")
        appendLine("         옷 이름을 그대로 나열하지 말고, 왜 함께 두었는지를 말해라.")
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
                    첫 번째 이미지는 한 사람의 전신 사진이다. 두 번째부터는 옷 사진이며,
                    **레이어 순서대로**(안쪽 → 바깥쪽) 주어진다.

                    이 사람이 그 옷들을 실제로 입고 같은 자리에서 찍은 것처럼 보이는
                    사진 한 장을 만들어라.

                    [반드시 그대로 둘 것]
                    - 얼굴, 머리 모양, 피부톤, 체형과 비율, 자세와 시선
                    - 배경, 촬영 각도, 화각, 조명의 방향과 색온도
                    - 사진의 전반적인 화질과 입자감

                    [바꿀 것]
                    - 입고 있는 옷만. 주어진 옷들로 완전히 교체한다.
                    - 원래 입고 있던 옷은 남기지 않는다. 겹쳐 그리지 마라.

                    [옷을 그릴 때]
                    - 각 옷의 색·무늬·재질·단추와 지퍼 같은 디테일을 사진 그대로 재현한다.
                    - 아우터는 상의 위에 오도록 겹쳐 입힌다.
                    - 몸에 닿는 곳의 주름, 접힘, 그림자를 자연스럽게 만든다.
                    - 옷이 배경 조명과 같은 방향으로 그림자를 받게 한다.

                    [금지]
                    - 글자, 워터마크, 로고 삽입
                    - 인물을 다른 사람으로 바꾸거나 얼굴을 보정하는 것
                    - 만화·일러스트 풍으로 바꾸는 것. **사진처럼 보여야 한다.**
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

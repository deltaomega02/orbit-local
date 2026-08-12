package com.orbit.ai

import com.orbit.domain.MainCategory

/**
 * AI 기능의 경계.
 *
 * 원본 Django 는 `genai.configure(...)` 로 모듈 레벨 싱글톤을 만들고 뷰에서 바로
 * 호출했다. 테스트에서 갈아끼울 자리가 없어서 AI 가 얽힌 코드는 아예 테스트가
 * 없었고, 결과적으로 "AI 응답이 이상할 때 서버가 어떻게 행동하는가"가 한 번도
 * 검증되지 않았다.
 *
 * 여기서는 반대로 간다. 애플리케이션 코드는 아래 세 인터페이스만 알고, 실제
 * Gemini 호출은 [com.orbit.ai.gemini] 의 어댑터에만 존재한다. 테스트는 결정적인
 * 가짜를 주입하므로 네트워크가 개입할 여지가 없다.
 */
interface ClothingAnalyzer {
    fun analyze(image: ByteArray, mime: String): ClothingAnalysis
}

interface OutfitRecommender {
    fun recommend(req: RecommendRequest): OutfitSuggestion
}

interface TryOnImageGenerator {
    /** 전신 사진 위에 옷을 입혀 본 이미지 바이트를 돌려준다. */
    fun generate(bodyPhoto: ByteArray, items: List<ByteArray>): ByteArray
}

data class ClothingAnalysis(
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val detail: String?,
)

/** 추천 후보. AI 에게는 이 목록에 있는 id 만 쓰라고 요구하고, 서버가 다시 검증한다. */
data class RecommendCandidate(
    val id: Long,
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val detail: String? = null,
)

data class RecommendRequest(
    val candidates: List<RecommendCandidate>,
    /** 오늘 이미 나온 조합. 프롬프트에 넣어 회피를 "요청"한다(보장은 서버가 한다). */
    val avoidCombinations: List<Set<Long>> = emptyList(),
)

data class OutfitSuggestion(
    val title: String,
    val reason: String,
    val clothesIds: List<Long>,
)

/** AI 를 쓸 수 없는 상태(키 미설정 등). 503 으로 변환된다. 나머지 기능은 정상 동작한다. */
class AiUnavailableException(message: String = "AI 기능을 사용할 수 없습니다") : RuntimeException(message)

/** 호출은 했지만 실패했다(타임아웃·5xx·재시도 소진). 502 로 변환된다. */
class AiCallFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 응답은 왔지만 서버 검증을 통과하지 못했다(예: 없는 clothesId). 502 로 변환된다. */
class AiInvalidResponseException(message: String) : RuntimeException(message)

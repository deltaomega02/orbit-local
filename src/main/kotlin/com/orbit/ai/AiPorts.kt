package com.orbit.ai

import com.orbit.domain.MainCategory

// AI 기능의 경계. 실제 Gemini 호출은 [com.orbit.ai.gemini] 어댑터에만 두고, 테스트는 가짜를 주입한다.
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

/**
 * 사진 한 장에서 읽어낸 값. 저장되지 않고 등록 폼의 초기값으로만 쓰인다.
 * 이름·카테고리 외에는 null 일 수 있고, 폴백도 같은 shape 를 돌려준다.
 */
data class ClothingAnalysis(
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val detail: String?,
    /** 셔츠·니트·청바지·코트 … [mainCategory] 를 한 단계 좁힌 종류. */
    val subCategory: String? = null,
    /** 면·울 혼방·데님·리넨·기모 … */
    val material: String? = null,
    /** 오버핏·슬림·와이드·레귤러 … */
    val fit: String? = null,
    /** 春・秋 / 夏 / 冬 / オールシーズン 중 하나([com.orbit.domain.Seasons]). 그 밖의 값은 어댑터가 버린다. */
    val season: String? = null,
)

/**
 * 추천 후보. AI 에게는 이 목록의 id 만 쓰게 하고, 서버가 다시 검증한다.
 * 값이 없는 속성은 프롬프트에서 생략한다(호출당 토큰 비용).
 */
data class RecommendCandidate(
    val id: Long,
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val detail: String? = null,
    val subCategory: String? = null,
    val material: String? = null,
    val fit: String? = null,
    val season: String? = null,
)

data class RecommendRequest(
    val candidates: List<RecommendCandidate>,
    /** 오늘 이미 나온 조합. 프롬프트에 넣어 회피를 "요청"한다(보장은 서버가 한다). */
    val avoidCombinations: List<Set<Long>> = emptyList(),
    /**
     * 사용자가 적은 취향(예: "카고팬츠 자주 넣어줘"). 필터가 아니라 우선순위로 쓴다.
     * 맞는 옷이 없을 때 필터로 동작하면 추천이 안 나오거나 모델이 옷을 지어낸다.
     */
    val stylePreference: String? = null,
    /**
     * 이번 추천에만 적용되는 상황("비 오고 쌀쌀해"). 취향처럼 우선순위로 쓴다.
     * 프롬프트에서 취향과 블록을 나누지 않으면 모델이 상황을 취향으로 읽는다.
     */
    val situation: String? = null,
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

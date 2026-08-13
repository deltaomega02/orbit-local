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

/**
 * 사진 한 장에서 읽어낸 값. 그대로 저장되지 않고 **등록 폼의 초기값**으로만 쓰인다.
 *
 * 이름·카테고리를 뺀 나머지는 전부 null 일 수 있다. 모델이 그 항목을 안 줬거나,
 * 준 값이 우리가 아는 형식이 아니었다는 뜻이다. 폴백도 같은 shape 라서 호출부는
 * "AI 가 성공했는가"를 분기하지 않는다.
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
 * 추천 후보. AI 에게는 이 목록에 있는 id 만 쓰라고 요구하고, 서버가 다시 검증한다.
 *
 * 속성이 늘어난 만큼 프롬프트도 길어지므로 **값이 없는 속성은 프롬프트에서 생략한다**
 * ([com.orbit.ai.gemini.GeminiOutfitRecommender.buildPrompt]). 옷 한 벌이 한 줄을
 * 넘지 않게 하는 것이 목표다 — 옷장이 100벌이면 그 차이가 그대로 호출당 비용이 된다.
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
     * 사용자가 자기 말로 적은 취향(예: "카고팬츠 자주 넣어줘"). 없으면 null.
     *
     * 이 값은 **후보를 거르는 조건이 아니라 우선순위**로 쓰인다. 취향에 맞는 옷이
     * 옷장에 없을 수도 있는데, 그때 필터처럼 동작하면 추천이 아예 안 나오거나
     * 모델이 없는 옷을 지어내게 된다. 그 경계는 프롬프트에서 명시한다.
     */
    val stylePreference: String? = null,
    /**
     * 사용자가 이번 추천에만 적어 준 오늘의 상황("비 오고 쌀쌀해", "면접 보러 가").
     *
     * [stylePreference] 와 **층위가 다르다.** 취향은 한 번 적어 두면 모든 추천에
     * 계속 적용되는 상수고, 상황은 오늘 이 한 번에만 붙는 변수다. 프롬프트에서도
     * 두 블록을 나눠 적는다 — 뭉쳐 놓으면 모델이 "면접 보러 가"를 앞으로도 계속
     * 지켜야 할 취향으로 읽는다.
     *
     * 취향과 마찬가지로 **조건이 아니라 우선순위**로 쓰인다. 비 오는 날에 맞는 옷이
     * 옷장에 없을 수도 있는데, 필터처럼 동작하면 추천이 안 나오거나 모델이 없는 옷을
     * 지어낸다. 그 경계는 프롬프트에서 명시한다.
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

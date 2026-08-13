package com.orbit.domain

/**
 * 계절 값의 표준 표기.
 *
 * ## 왜 값 자체를 일본어로 바꿨나 (표시만 바꾸지 않고)
 *
 * `season` 은 **화면에 보이는 라벨이자 프롬프트에 실려 나가는 데이터**다. 추천
 * 프롬프트는 옷 목록에 `季節:夏` 처럼 저장된 값을 그대로 적어 넣고, 규칙에서
 * 그 값을 직접 가리킨다("`季節:夏` 와 `季節:冬` 을 한 벌에 섞지 마라").
 *
 * 그래서 "저장은 한국어, 표시만 일본어"는 이 앱에서 성립하지 않는다.
 *  - 프롬프트에 실리는 것은 저장된 값이라, 모델은 계속 한국어 라벨을 보게 된다.
 *  - 사진 분석은 이제 일본어로 답한다. 그 값을 한국어로 되돌려 저장하려면
 *    **양방향 번역표**가 필요하고, 표에 없는 표기가 하나만 들어와도 규칙 비교가
 *    깨진다.
 *  - 결국 DB 안에 두 언어가 섞이고, 어느 쪽이 진짜인지는 코드를 읽어야만 안다.
 *
 * 그래서 표준 표기 자체를 일본어로 옮기고, **기존 한국어 값은 별칭으로 받아
 * 표준값으로 눌러 준다**([normalize]). 이미 저장된 행은 기동 시 한 번 도는
 * [com.orbit.service.SeasonBackfill] 이 옮긴다.
 *
 * ## 자유 입력은 막지 않는다
 *
 * 네 값으로 좁히는 것은 **AI 가 답하는 방식**의 제약이지 사용자 입력의 제약이
 * 아니다([Clothes.season] 주석). 여기 목록에 없는 값은 그대로 통과시킨다 —
 * 사용자가 자기 옷을 자기 말로 적을 수 있어야 한다.
 */
object Seasons {
    const val SPRING_AUTUMN = "春・秋"
    const val SUMMER = "夏"
    const val WINTER = "冬"
    const val ALL = "オールシーズン"

    /**
     * 허용 값. **프롬프트·responseSchema·파싱이 이 하나를 본다.**
     * 세 곳에 따로 적으면 스키마에는 있는데 파싱이 버리는 값이 생긴다.
     */
    val CANONICAL = listOf(SPRING_AUTUMN, SUMMER, WINTER, ALL)

    /**
     * 예전에 쓰던 한국어 표기 → 표준값.
     *
     * 두 종류가 섞여 있다.
     *  - `봄·가을`·`여름`·`겨울`·`사계절` — 예전 표준값. 이미 DB 에 들어 있다.
     *  - `봄가을`·`간절기` — 사용자가 손으로 적었을 법한 변종. 프롬프트가 만든
     *    값은 아니지만, 손 입력이 열려 있는 필드라 실제로 들어올 수 있다.
     */
    private val ALIASES = mapOf(
        "봄·가을" to SPRING_AUTUMN,
        "봄가을" to SPRING_AUTUMN,
        "간절기" to SPRING_AUTUMN,
        "여름" to SUMMER,
        "겨울" to WINTER,
        "사계절" to ALL,
        // 通年 — 의류에서 널리 쓰는 표기다. 화면 칩이 한때 이 값을 보내고 있었고,
        // 사용자가 손으로 적을 가능성도 높다. 표준값으로 흡수해 DB 에 한 언어만 남긴다.
        "通年" to ALL,
    )

    /** 옮길 대상. 백필이 이 목록만 훑는다. */
    val LEGACY_TO_CANONICAL: Map<String, String> get() = ALIASES

    /**
     * 아는 표기면 표준값으로, 모르는 값이면 그대로 돌려준다.
     *
     * 저장 직전에 한 번 통과시킨다([com.orbit.service.ClothesTraits.normalized]).
     * 화면이 아직 한국어 칩을 보내더라도 DB 에는 한 언어만 남게 하려는 것이다 —
     * 값이 섞이는 순간 추천의 계절 비교가 조용히 어긋난다.
     */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        return ALIASES[trimmed] ?: trimmed
    }
}

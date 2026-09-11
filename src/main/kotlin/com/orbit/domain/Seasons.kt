package com.orbit.domain

/**
 * 계절 표준 표기. 저장값이 프롬프트 규칙(`季節:夏` 등)에서 그대로 비교되므로 값 자체를 일본어로 둔다.
 * 옛 한국어 값은 [normalize] 로 흡수하고, 목록에 없는 자유 입력은 그대로 통과시킨다.
 */
object Seasons {
    const val SPRING_AUTUMN = "春・秋"
    const val SUMMER = "夏"
    const val WINTER = "冬"
    const val ALL = "オールシーズン"

    /** 프롬프트·responseSchema·파싱이 함께 쓰는 허용 값. */
    val CANONICAL = listOf(SPRING_AUTUMN, SUMMER, WINTER, ALL)

    // 옛 표준값(봄·가을 등)과 손으로 입력했을 법한 변종
    private val ALIASES = mapOf(
        "봄·가을" to SPRING_AUTUMN,
        "봄가을" to SPRING_AUTUMN,
        "간절기" to SPRING_AUTUMN,
        "여름" to SUMMER,
        "겨울" to WINTER,
        "사계절" to ALL,
        "通年" to ALL,
    )

    /** SeasonBackfill 이 옮길 대상. */
    val LEGACY_TO_CANONICAL: Map<String, String> get() = ALIASES

    /** 아는 표기는 표준값으로, 모르는 값은 그대로 돌려준다. 저장 직전에 호출한다. */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        return ALIASES[trimmed] ?: trimmed
    }
}

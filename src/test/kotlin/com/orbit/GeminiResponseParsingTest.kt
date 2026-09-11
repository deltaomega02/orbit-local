package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.RecommendCandidate
import com.orbit.ai.RecommendRequest
import com.orbit.ai.gemini.GeminiClient
import com.orbit.ai.gemini.GeminiKeyStore
import com.orbit.ai.gemini.GeminiClothingAnalyzer
import com.orbit.ai.gemini.GeminiOutfitRecommender
import com.orbit.ai.gemini.GeminiProperties
import com.orbit.domain.MainCategory
import com.orbit.domain.Seasons
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 어댑터의 응답 해석만 `internal` 함수로 떼어 검증한다. 네트워크는 쓰지 않는다.
 * 모델이 무엇을 돌려줘도 호출부는 같은 shape 를 받아야 한다.
 */
@DisplayName("Gemini 응답 해석 — 스키마 위반 시 폴백")
class GeminiResponseParsingTest {

    private val objectMapper = ObjectMapper()
    private val properties = GeminiProperties(apiKey = "")
    // 네트워크를 타지 않으므로 키 저장소는 빈 것으로 둔다
    private val client = GeminiClient(properties, GeminiKeyStore())

    private val recommender = GeminiOutfitRecommender(client, properties, objectMapper)
    private val analyzer = GeminiClothingAnalyzer(client, properties, objectMapper)

    private val request = RecommendRequest(
        candidates = listOf(
            RecommendCandidate(1L, "흰 셔츠", MainCategory.TOP, "화이트"),
            RecommendCandidate(2L, "청바지", MainCategory.BOTTOM, "인디고"),
            RecommendCandidate(3L, "코트", MainCategory.OUTER, "네이비"),
        ),
    )

    @Test
    fun `정상 JSON 은 그대로 해석한다`() {
        val parsed = recommender.parseOrFallback(
            """{"title":"봄 나들이","reason":"밝은 톤끼리 맞췄습니다.","clothesIds":[1,2]}""",
            request,
        )

        assertEquals("봄 나들이", parsed.title)
        assertEquals("밝은 톤끼리 맞췄습니다.", parsed.reason)
        assertEquals(listOf(1L, 2L), parsed.clothesIds)
    }

    @Test
    fun `JSON 이 아니면 같은 shape 의 폴백을 돌려준다`() {
        val parsed = recommender.parseOrFallback("흰 셔츠에 청바지를 추천드려요!", request)

        // 폴백도 반드시 실존하는 후보 id 로만 구성된다
        assertEquals(listOf(1L, 2L), parsed.clothesIds)
        assertTrue(parsed.reason.isNotBlank())
        assertTrue(parsed.title.isNotBlank())
    }

    @Test
    fun `clothesIds 가 비어 있으면 폴백으로 넘어간다`() {
        val parsed = recommender.parseOrFallback("""{"title":"코디","reason":"이유","clothesIds":[]}""", request)

        assertEquals(listOf(1L, 2L), parsed.clothesIds)
    }

    @Test
    fun `모르는 mainCategory 는 폴백 값으로 채운다`() {
        val parsed = analyzer.parseOrFallback("""{"name":"모자","mainCategory":"HAT","color":"블랙"}""")

        assertEquals("모자", parsed.name)
        assertEquals(MainCategory.TOP, parsed.mainCategory) // 모르는 값에 서버가 넘어가지 않는다
        assertEquals("블랙", parsed.color)
    }

    @Test
    fun `분석 응답이 깨져도 등록 폼을 채울 값은 항상 나온다`() {
        val parsed = analyzer.parseOrFallback("응답이 잘렸습니다 {\"name\":")

        assertEquals(GeminiClothingAnalyzer.FALLBACK, parsed)
        // 폴백에도 속성축 자리가 있어(전부 null) 호출부가 shape 을 분기하지 않는다
        assertNull(parsed.subCategory)
        assertNull(parsed.material)
        assertNull(parsed.fit)
        assertNull(parsed.season)
    }

    // --- 속성축 (subCategory, material, fit, season)

    @Test
    fun `속성축이 전부 오면 그대로 읽는다`() {
        val parsed = analyzer.parseOrFallback(
            """
            {"name":"네이비 옥스퍼드 셔츠","mainCategory":"TOP","color":"네이비",
             "subCategory":"シャツ","material":"コットン","fit":"レギュラー","season":"春・秋",
             "detail":"胸に小さな刺繍のロゴ"}
            """.trimIndent(),
        )

        assertEquals("シャツ", parsed.subCategory)
        assertEquals("コットン", parsed.material)
        assertEquals("レギュラー", parsed.fit)
        assertEquals("春・秋", parsed.season)
        assertEquals("胸に小さな刺繍のロゴ", parsed.detail)
    }

    /**
     * responseSchema 의 `required` 는 name·mainCategory 뿐이라 나머지가 빠져도 정상이다.
     * 폴백으로 넘어가면 소재 하나 때문에 이름·색까지 버려진다.
     */
    @Test
    fun `일부 속성만 와도 나머지만 비우고 폴백으로 새지 않는다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"청바지","mainCategory":"BOTTOM","material":"데님"}""",
        )

        assertEquals("청바지", parsed.name)
        assertEquals(MainCategory.BOTTOM, parsed.mainCategory)
        assertEquals("데님", parsed.material)
        assertNull(parsed.subCategory)
        assertNull(parsed.fit)
        assertNull(parsed.season)
        assertNull(parsed.color)
    }

    @Test
    fun `공백만 있는 속성은 없는 것으로 본다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"셔츠","mainCategory":"TOP","material":"   ","fit":""}""",
        )

        assertNull(parsed.material, "공백만 남는 값을 저장하면 화면에 빈 항목이 생긴다")
        assertNull(parsed.fit)
    }

    /**
     * 스키마의 enum 은 요청이지 보장이 아니다(mainCategory 를 이미 그렇게 다룬다).
     * 목록 밖의 표기가 섞이면 추천 규칙의 `季節:夏` ↔ `季節:冬` 비교가 성립하지 않는다.
     */
    @Test
    fun `모르는 season 값은 채우지 않고 비운다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"シャツ","mainCategory":"TOP","season":"梅雨","material":"コットン"}""",
        )

        assertNull(parsed.season, "네 값 밖의 표기는 버린다")
        assertEquals("コットン", parsed.material, "season 하나 때문에 다른 값까지 버리면 안 된다")
    }

    @Test
    fun `허용된 season 네 값은 모두 통과한다`() {
        Seasons.CANONICAL.forEach { season ->
            val parsed = analyzer.parseOrFallback(
                """{"name":"服","mainCategory":"TOP","season":"$season"}""",
            )
            assertEquals(season, parsed.season)
        }
    }

    /**
     * 캐시된 프롬프트, 재시도, 모델 교체 등으로 옛 표기가 올 수 있다.
     * 버리면 빈 칸이 되고 그대로 저장하면 옷장에 두 언어가 섞인다.
     */
    @Test
    fun `옛 한국어 계절 표기로 답해도 표준 표기로 바꿔 받는다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"ニット","mainCategory":"TOP","season":"겨울"}""",
        )

        assertEquals(Seasons.WINTER, parsed.season)
    }

    /** 컬럼 길이를 넘는 값이 폼에 들어가면 그대로 저장할 때 400 이 된다. */
    @Test
    fun `너무 긴 속성값은 컬럼 길이에서 잘라 내려준다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"셔츠","mainCategory":"TOP","material":"${"면".repeat(100)}"}""",
        )

        assertEquals(30, parsed.material?.length)
    }
}

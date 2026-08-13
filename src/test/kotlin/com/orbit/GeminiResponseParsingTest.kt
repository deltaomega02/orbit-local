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
 * 실제 어댑터의 **응답 해석**만 떼어 검증한다.
 *
 * 파싱을 `internal` 함수로 빼 둔 덕분에 HTTP 를 한 번도 타지 않고 "모델이 이상한 걸
 * 돌려줬을 때"를 확인할 수 있다. 이 테스트가 도는 동안 네트워크는 전혀 쓰이지 않는다
 * (GeminiClient 는 만들어만 두고 호출하지 않는다).
 *
 * 실제 모델이 어떻게 응답하는지는 여기서 검증할 수 없다 — 그건 통합 테스트의 몫이고,
 * 이 저장소는 그걸 CI 에서 돌리지 않는다. 여기서 고정하는 것은 "어떤 쓰레기가 와도
 * 호출부는 항상 같은 shape 를 받는다"는 것 하나다.
 */
@DisplayName("Gemini 응답 해석 — 스키마 위반 시 폴백")
class GeminiResponseParsingTest {

    private val objectMapper = ObjectMapper()
    private val properties = GeminiProperties(apiKey = "")
    // 이 테스트는 파싱만 검증한다. 네트워크를 타지 않으므로 키 저장소도 빈 것으로 둔다.
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
        // 폴백에도 속성축 자리는 있다(전부 null). 호출부가 shape 을 분기하지 않는다는 것이 요점이다
        assertNull(parsed.subCategory)
        assertNull(parsed.material)
        assertNull(parsed.fit)
        assertNull(parsed.season)
    }

    // ── 속성축(subCategory · material · fit · season) ──────────────────

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
     * responseSchema 의 `required` 는 name·mainCategory 뿐이다. 나머지가 빠지는 것은
     * 정상이며 **폴백으로 넘어갈 일이 아니다** — 빠진 자리만 비우고 나머지는 살린다.
     * 여기서 폴백으로 새면 모델이 소재 하나를 안 줬다는 이유로 이름·색까지 버려진다.
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
     * 프롬프트를 일본어로 바꿨어도 모델이 옛 표기로 답할 수 있다(캐시된 프롬프트,
     * 재시도, 모델 교체 등). 그때 값을 버리면 사용자는 이유 없이 빈 칸을 보게 되고,
     * 그대로 저장하면 옷장에 두 언어가 섞인다. 그래서 눌러서 받는다.
     */
    @Test
    fun `옛 한국어 계절 표기로 답해도 표준 표기로 바꿔 받는다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"ニット","mainCategory":"TOP","season":"겨울"}""",
        )

        assertEquals(Seasons.WINTER, parsed.season)
    }

    /** 컬럼 길이를 넘는 값이 폼에 들어가면, 사용자가 그대로 저장을 눌렀을 때 400 이 된다. */
    @Test
    fun `너무 긴 속성값은 컬럼 길이에서 잘라 내려준다`() {
        val parsed = analyzer.parseOrFallback(
            """{"name":"셔츠","mainCategory":"TOP","material":"${"면".repeat(100)}"}""",
        )

        assertEquals(30, parsed.material?.length)
    }
}

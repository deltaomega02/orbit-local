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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
    }
}

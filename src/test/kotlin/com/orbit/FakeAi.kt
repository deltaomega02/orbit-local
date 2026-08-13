package com.orbit

import com.orbit.ai.ClothingAnalysis
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.OutfitSuggestion
import com.orbit.ai.RecommendRequest
import com.orbit.ai.TryOnImageGenerator
import com.orbit.domain.MainCategory
import com.orbit.domain.Seasons
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 테스트용 이미지. 매직 바이트가 진짜여야 [com.orbit.media.ImageType.detect] 를
 * 통과하므로, 손으로 만든 가짜 헤더 대신 실제 PNG 를 인코딩한다.
 */
fun pngBytes(size: Int = 4): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", out)
    return out.toByteArray()
}

/**
 * 결정적인 AI 가짜들.
 *
 * 원본 Django 는 `genai` 모듈 싱글톤을 직접 불러서 이 자리에 끼워 넣을 것이 없었고,
 * 그래서 AI 가 관련된 코드는 테스트가 아예 없었다. 인터페이스로 갈라 두니 "AI 가
 * 이상한 답을 했을 때 서버가 어떻게 행동하는가"를 실제로 검증할 수 있다.
 *
 * 호출 횟수를 세는 것도 중요한데, 가상 착용의 멱등성은 "결과가 같다"가 아니라
 * "두 번째에는 생성기를 부르지 않는다"로만 증명할 수 있기 때문이다.
 */
class FakeClothingAnalyzer : ClothingAnalyzer {
    var calls = 0
    var lastMime: String? = null

    /**
     * 실제로 넘어간 바이트. AI 로 보내기 전에 사진을 줄이는지는 **여기서만** 볼 수 있다 —
     * 응답만 보면 4000px 원본을 그대로 실어 보내도 테스트는 초록색이다.
     */
    var lastImage: ByteArray? = null
    var result = defaultResult()

    override fun analyze(image: ByteArray, mime: String): ClothingAnalysis {
        calls++
        lastMime = mime
        lastImage = image
        return result
    }

    fun reset() {
        calls = 0
        lastMime = null
        lastImage = null
        result = defaultResult()
    }

    companion object {
        /**
         * 기본 분석 결과. 속성축이 **전부 채워진** 경우다 — 등록 폼이 그 값을 그대로
         * 받는지 확인하려면 기본값에 값이 있어야 한다. 일부만 오는 경우는 테스트가
         * [result] 를 직접 갈아끼워 만든다.
         */
        fun defaultResult() = ClothingAnalysis(
            // 값은 일본어다. 실제 모델이 일본어로 답하도록 프롬프트를 바꿨으므로,
            // 가짜가 한국어를 돌려주면 테스트는 존재하지 않는 상황을 검증하게 된다.
            name = "白いリネンシャツ",
            mainCategory = MainCategory.TOP,
            color = "ホワイト",
            detail = "リネン素材の夏のシャツ",
            subCategory = "シャツ",
            material = "リネン",
            fit = "レギュラー",
            season = Seasons.SUMMER,
        )
    }
}

class FakeOutfitRecommender : OutfitRecommender {
    var calls = 0
    var lastRequest: RecommendRequest? = null

    /** 테스트가 이 람다를 바꿔서 "AI 가 헛소리를 한 상황"을 만든다. */
    var behavior: (RecommendRequest) -> OutfitSuggestion = ::pickTopAndBottom

    override fun recommend(req: RecommendRequest): OutfitSuggestion {
        calls++
        lastRequest = req
        return behavior(req)
    }

    fun reset() {
        calls = 0
        lastRequest = null
        behavior = ::pickTopAndBottom
    }

    companion object {
        /** 기본 동작: 아직 오늘 나오지 않은 상의·하의 조합을 하나 고른다. */
        fun pickTopAndBottom(req: RecommendRequest): OutfitSuggestion {
            val tops = req.candidates.filter { it.mainCategory == MainCategory.TOP }
            val bottoms = req.candidates.filter { it.mainCategory == MainCategory.BOTTOM }
            val combo = tops.asSequence()
                .flatMap { top -> bottoms.asSequence().map { bottom -> listOf(top.id, bottom.id) } }
                .firstOrNull { it.toSet() !in req.avoidCombinations }
                ?: listOf(tops.first().id, bottoms.first().id)
            return OutfitSuggestion("今日のコーデ", "無理なくまとまる組み合わせです。", combo)
        }
    }
}

class FakeTryOnImageGenerator : TryOnImageGenerator {
    var calls = 0
    var lastItemCount = 0
    var result: ByteArray = pngBytes(8)

    override fun generate(bodyPhoto: ByteArray, items: List<ByteArray>): ByteArray {
        calls++
        lastItemCount = items.size
        return result
    }

    fun reset() {
        calls = 0
        lastItemCount = 0
    }
}

/**
 * 가짜 AI 를 등록하는 설정. **명시적으로 `@Import` 한 테스트에만** 붙는다.
 *
 * 자동으로 붙지 않게 한 것이 요점이다. 가져오지 않은 테스트의 컨텍스트에는 AI 빈이
 * 하나도 없고, 그건 곧 "키를 설정하지 않은 실제 환경"과 같은 상태다. 그 상태에서
 * 옷장 기능이 멀쩡한지를 [AiUnavailableTest] 가 확인한다.
 */
@TestConfiguration
class FakeAiConfig {
    @Bean fun fakeClothingAnalyzer() = FakeClothingAnalyzer()

    @Bean fun fakeOutfitRecommender() = FakeOutfitRecommender()

    @Bean fun fakeTryOnImageGenerator() = FakeTryOnImageGenerator()
}

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

/** [com.orbit.media.ImageType.detect] 의 매직 바이트 검사를 통과하도록 실제 PNG 를 인코딩한다. */
fun pngBytes(size: Int = 4): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", out)
    return out.toByteArray()
}

/**
 * 결정적인 AI 가짜들.
 * 가상 착용의 멱등성은 두 번째에 생성기를 부르지 않는 것으로만 확인되므로 호출 횟수를 센다.
 */
class FakeClothingAnalyzer : ClothingAnalyzer {
    var calls = 0
    var lastMime: String? = null

    /** AI 로 보내기 전에 사진을 줄였는지는 이 값으로만 확인할 수 있다. */
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
        /** 속성축이 모두 채워진 결과. 일부만 오는 경우는 테스트가 [result] 를 바꿔 만든다. */
        fun defaultResult() = ClothingAnalysis(
            // 실제 모델이 일본어로 답하므로 가짜도 일본어로 돌려준다
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

    /** 테스트가 이 람다를 바꿔 잘못된 AI 응답을 흉내 낸다. */
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
 * 가짜 AI 설정. `@Import` 한 테스트에만 붙는다.
 * 가져오지 않은 컨텍스트는 키를 설정하지 않은 환경과 같다([AiUnavailableTest]).
 */
@TestConfiguration
class FakeAiConfig {
    @Bean fun fakeClothingAnalyzer() = FakeClothingAnalyzer()

    @Bean fun fakeOutfitRecommender() = FakeOutfitRecommender()

    @Bean fun fakeTryOnImageGenerator() = FakeTryOnImageGenerator()
}

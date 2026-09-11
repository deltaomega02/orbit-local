package com.orbit.service

import com.orbit.ai.AiInvalidResponseException
import com.orbit.ai.AiUnavailableException
import com.orbit.ai.ClothingAnalysis
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.RecommendCandidate
import com.orbit.ai.RecommendRequest
import com.orbit.ai.TryOnImageGenerator
import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.CoordinationLimits
import com.orbit.domain.MainCategory
import com.orbit.media.ImageNormalizer
import com.orbit.media.ImageType
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/** 상의·하의가 각각 최소 1벌은 있어야 조합이 성립한다. 400 으로 변환된다. */
class NotEnoughClothesException : RuntimeException("코디를 만들려면 상의와 하의가 각각 최소 1벌 필요합니다")

/**
 * 오늘 만들 수 있는 조합을 모두 썼다. 409 `exhausted` 로 변환된다.
 * 재시도하면 되는 [DuplicateCoordinationException] 과 달리 다시 시도해도 새 조합이 없다.
 */
class CombinationsExhaustedException(val possible: Long, val used: Long) :
    RuntimeException("오늘 만들 수 있는 조합을 모두 사용했습니다 ($used/$possible)")

/** 전신 사진 없이 가상 착용을 요청했다. 400 으로 변환된다. */
class NoBodyPhotoException : RuntimeException("전신 사진을 먼저 등록해 주세요")

class CoordinationNotFoundException(val id: Long) : RuntimeException("코디를 찾을 수 없습니다: $id")

/**
 * AI 기능(분석·추천·가상 착용) 서비스. AI 호출 중 DB 커넥션을 잡지 않도록 클래스 레벨 트랜잭션을 두지 않는다.
 * AI 를 꺼둔 환경에서도 컨텍스트가 뜨도록 구현은 [ObjectProvider] 로 받는다.
 */
@Service
class OutfitAiService(
    private val clothesRepository: ClothesRepository,
    private val coordinationRepository: CoordinationRepository,
    private val userRepository: UserRepository,
    private val coordinationService: CoordinationService,
    private val mediaStorage: MediaStorage,
    private val imageNormalizer: ImageNormalizer,
    private val analyzerProvider: ObjectProvider<ClothingAnalyzer>,
    private val recommenderProvider: ObjectProvider<OutfitRecommender>,
    private val tryOnProvider: ObjectProvider<TryOnImageGenerator>,
) {

    /** 사진만 보고 등록 폼에 채울 값을 제안한다. 저장은 하지 않는다. */
    fun analyze(image: ByteArray, mime: String): ClothingAnalysis =
        analyzerProvider.require().analyze(image, mime)

    /**
     * 오늘의 조합을 추천한다. 남은 조합이 없으면 AI 를 부르지 않고 [CombinationsExhaustedException].
     * 중복은 프롬프트로 피하게 하고 [CoordinationService.create] 가 409 로 막는다.
     * 응답의 id 소유 여부와 구성([requireOutfitShape])은 서버가 다시 검사하고, [situation] 은 코디에 함께 저장한다.
     */
    fun recommend(ownerId: Long, situation: String? = null): Coordination {
        val recommender = recommenderProvider.require()
        // 저장·프롬프트가 같은 값을 쓰도록 여기서 한 번만 정규화한다.
        val context = situation?.trim()?.takeIf { it.isNotBlank() }?.take(CoordinationLimits.SITUATION)

        // 소프트 삭제된 옷은 후보에서 뺀다.
        val wardrobe = clothesRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId)
        val hasTop = wardrobe.any { it.mainCategory == MainCategory.TOP }
        val hasBottom = wardrobe.any { it.mainCategory == MainCategory.BOTTOM }
        if (!hasTop || !hasBottom) throw NotEnoughClothesException()

        val today = coordinationService.todayCoordinations(ownerId)

        // AI 호출 전에 남은 조합 수를 확인한다.
        val possible = RecommendableCombinations.total(wardrobe)
        val used = RecommendableCombinations.usedToday(wardrobe, today)
        if (used >= possible) throw CombinationsExhaustedException(possible = possible, used = used)

        val suggestion = recommender.recommend(
            RecommendRequest(
                candidates = wardrobe.map {
                    RecommendCandidate(
                        id = requireNotNull(it.id),
                        name = it.name,
                        mainCategory = it.mainCategory,
                        color = it.color,
                        detail = it.detail,
                        subCategory = it.subCategory,
                        material = it.material,
                        fit = it.fit,
                        season = it.season,
                    )
                },
                avoidCombinations = today.map { it.clothesIdSet() },
                // 프롬프트 반영 여부는 StylePreferenceTest 에서 확인한다.
                stylePreference = userRepository.findById(ownerId).orElse(null)?.stylePreference,
                situation = context,
            ),
        )

        // 모델이 없는 id 나 남의 옷 id 를 낼 수 있으므로 소유 목록과 대조한다(IDOR 방지).
        val ownedIds = wardrobe.mapNotNull { it.id }.toSet()
        val suggested = suggestion.clothesIds.distinct()
        if (suggested.isEmpty() || !ownedIds.containsAll(suggested)) {
            throw AiInvalidResponseException("추천 결과에 소유하지 않은 의류 id 가 있습니다: ${suggested - ownedIds}")
        }

        val byId = wardrobe.associateBy { it.id }
        requireOutfitShape(suggested.mapNotNull { byId[it]?.mainCategory })

        return coordinationService.create(ownerId, suggestion.title, suggested, suggestion.reason, context)
    }

    /**
     * 추천 결과가 상의 1 + 하의 1 + 아우터 0~1 인지 검사하고, 아니면 502 로 거절한다.
     * 잘라서 저장하면 reason 과 구성이 어긋나므로 고치지 않는다.
     * [RecommendableCombinations] 의 조합 수 식과 같은 규격을 유지해야 한다.
     */
    private fun requireOutfitShape(categories: List<MainCategory>) {
        val counts = categories.groupingBy { it }.eachCount()
        val tops = counts[MainCategory.TOP] ?: 0
        val bottoms = counts[MainCategory.BOTTOM] ?: 0
        val outers = counts[MainCategory.OUTER] ?: 0
        if (tops != 1 || bottoms != 1 || outers > 1) {
            throw AiInvalidResponseException(
                "추천 결과의 구성이 한 벌이 아닙니다 (상의 $tops · 하의 $bottoms · 아우터 $outers)",
            )
        }
    }

    /** 가상 착용 이미지를 만든다. 호출당 과금되므로 이미 있으면 기존 경로를 돌려준다(멱등). */
    fun generateTryOn(ownerId: Long, coordinationId: Long): String {
        val coordination = coordinationRepository.findByIdAndOwnerIdWithItems(coordinationId, ownerId)
            ?: throw CoordinationNotFoundException(coordinationId)
        coordination.tryOnImagePath?.let { return it }

        val generator = tryOnProvider.require()
        val user = userRepository.findById(ownerId).orElseThrow { NoBodyPhotoException() }
        val bodyPhotoPath = user.bodyPhotoPath ?: throw NoBodyPhotoException()
        val bodyPhoto = mediaStorage.read(bodyPhotoPath) ?: throw NoBodyPhotoException()

        // 사진이 없는 옷은 건너뛴다.
        val itemImages = coordination.items
            .mapNotNull { it.clothes.imagePath }
            .mapNotNull { mediaStorage.read(it) }

        val generated = generator.generate(bodyPhoto, itemImages)
        // 출력 크기가 입력 사진을 따라가므로 목록에서 크기를 맞추려고 고정 캔버스에 앉힌다([MediaProperties.tryonCanvasWidth]).
        val canvased = imageNormalizer.fitToTryonCanvas(generated, ImageType.detect(generated) ?: ImageType.JPEG)
        val stored = mediaStorage.store("tryon", canvased.bytes, canvased.type.mime)
        coordinationRepository.updateTryOnImagePath(coordinationId, ownerId, stored.relativePath)
        return stored.relativePath
    }

    /** 빈이 없으면 AI 를 쓸 수 없는 환경이므로 503. */
    private fun <T : Any> ObjectProvider<T>.require(): T =
        getIfAvailable() ?: throw AiUnavailableException()
}

/**
 * AI 호출 전에 오늘 남은 조합이 있는지 판정한다. 조합 수 = 상의 × 하의 × (아우터 + 1).
 * 프롬프트 규칙([com.orbit.ai.gemini.GeminiOutfitRecommender.buildPrompt])을 바꾸면 이 식도 바꿔야 한다.
 * 수동 생성 코디처럼 추천 규격 밖의 조합은 세지 않아, 틀려도 AI 를 한 번 더 부르는 쪽으로만 틀린다.
 */
internal object RecommendableCombinations {

    /** 이 옷장으로 만들 수 있는 조합의 수. */
    fun total(wardrobe: List<Clothes>): Long {
        val tops = wardrobe.count { it.mainCategory == MainCategory.TOP }.toLong()
        val bottoms = wardrobe.count { it.mainCategory == MainCategory.BOTTOM }.toLong()
        val outers = wardrobe.count { it.mainCategory == MainCategory.OUTER }.toLong()
        return tops * bottoms * (outers + 1)
    }

    /** 오늘 쓴 조합 중 추천 규격에 맞는 것의 수. 중복 판정에 기대지 않고 distinct 로 [total] 을 넘지 않게 한다. */
    fun usedToday(wardrobe: List<Clothes>, today: List<Coordination>): Long {
        val inWardrobe = wardrobe.mapNotNull { it.id }.toSet()
        return today.asSequence()
            .filter { it.isRecommendableShape(inWardrobe) }
            .map { it.clothesIdSet() }
            .distinct()
            .count()
            .toLong()
    }

    /** 현재 옷장의 옷으로만 상의 1·하의 1·아우터 0~1 인지. */
    private fun Coordination.isRecommendableShape(inWardrobe: Set<Long>): Boolean {
        val clothes = items.map { it.clothes }
        if (clothes.any { it.id !in inWardrobe }) return false
        val byCategory = clothes.groupingBy { it.mainCategory }.eachCount()
        return byCategory[MainCategory.TOP] == 1 &&
            byCategory[MainCategory.BOTTOM] == 1 &&
            (byCategory[MainCategory.OUTER] ?: 0) <= 1
    }
}

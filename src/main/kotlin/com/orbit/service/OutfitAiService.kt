package com.orbit.service

import com.orbit.ai.AiInvalidResponseException
import com.orbit.ai.AiUnavailableException
import com.orbit.ai.ClothingAnalysis
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.RecommendCandidate
import com.orbit.ai.RecommendRequest
import com.orbit.ai.TryOnImageGenerator
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/** 상의·하의가 각각 최소 1벌은 있어야 조합이 성립한다. 400 으로 변환된다. */
class NotEnoughClothesException : RuntimeException("코디를 만들려면 상의와 하의가 각각 최소 1벌 필요합니다")

/** 전신 사진 없이 가상 착용을 요청했다. 400 으로 변환된다. */
class NoBodyPhotoException : RuntimeException("전신 사진을 먼저 등록해 주세요")

class CoordinationNotFoundException(val id: Long) : RuntimeException("코디를 찾을 수 없습니다: $id")

/**
 * AI 가 관여하는 세 기능(분석·추천·가상 착용)의 애플리케이션 서비스.
 *
 * **클래스 레벨 `@Transactional` 이 없는 것은 의도적이다.** 여기서 하는 일의 대부분은
 * 수 초짜리 외부 HTTP 호출이다. 트랜잭션 안에서 그걸 기다리면 그동안 DB 커넥션이
 * 묶여 있고, 사용자가 조금만 몰려도 커넥션 풀이 먼저 마른다. 그래서 읽기 → (트랜잭션
 * 밖) AI 호출 → 쓰기 순서로 쪼개고, 실제 쓰기는 [CoordinationService] 와 리포지토리의
 * 짧은 트랜잭션에 맡긴다.
 *
 * AI 구현을 [ObjectProvider] 로 받는 이유: 키가 없거나 AI 를 꺼둔 환경에서도 이
 * 애플리케이션은 옷장 앱으로서 온전히 동작해야 한다. 필수 의존성으로 잡으면 빈이
 * 없을 때 컨텍스트 자체가 못 뜬다.
 */
@Service
class OutfitAiService(
    private val clothesRepository: ClothesRepository,
    private val coordinationRepository: CoordinationRepository,
    private val userRepository: UserRepository,
    private val coordinationService: CoordinationService,
    private val mediaStorage: MediaStorage,
    private val analyzerProvider: ObjectProvider<ClothingAnalyzer>,
    private val recommenderProvider: ObjectProvider<OutfitRecommender>,
    private val tryOnProvider: ObjectProvider<TryOnImageGenerator>,
) {

    /** 사진만 보고 등록 폼에 채울 값을 제안한다. 저장은 하지 않는다. */
    fun analyze(image: ByteArray, mime: String): ClothingAnalysis =
        analyzerProvider.require().analyze(image, mime)

    /**
     * 옷장에서 오늘의 조합을 추천한다.
     *
     * 중복 회피는 세 겹이다.
     *  1) 프롬프트에 오늘 나온 조합을 넣어 피하라고 지시한다 (확률적, 보장 없음)
     *  2) 서버가 당일 코디와 집합 비교로 중복을 판정한다 ([CoordinationService.create])
     *  3) 중복이면 409 + `retry: true` 로 클라이언트가 다시 요청한다
     *
     * 1번만 믿는 구현이 원본이었고, 실제로 같은 조합이 반복해서 나왔다.
     */
    fun recommend(ownerId: Long): Coordination {
        val recommender = recommenderProvider.require()

        val wardrobe = clothesRepository.findAllByOwnerId(ownerId)
        val hasTop = wardrobe.any { it.mainCategory == MainCategory.TOP }
        val hasBottom = wardrobe.any { it.mainCategory == MainCategory.BOTTOM }
        if (!hasTop || !hasBottom) throw NotEnoughClothesException()

        val suggestion = recommender.recommend(
            RecommendRequest(
                candidates = wardrobe.map {
                    RecommendCandidate(requireNotNull(it.id), it.name, it.mainCategory, it.color)
                },
                avoidCombinations = coordinationService.todayCoordinations(ownerId).map { it.clothesIdSet() },
            ),
        )

        /*
         * AI 가 돌려준 id 를 그대로 믿지 않는다.
         *
         * responseSchema 로 "숫자 배열"이라는 모양은 강제할 수 있어도, 그 숫자가
         * 실재하는 내 옷인지는 강제할 방법이 없다. 모델은 후보에 없던 id 를 태연히
         * 지어낼 수 있고, 최악의 경우 남의 옷 id 를 찍을 수도 있다. 여기서 소유
         * 목록과 대조하지 않으면 그게 곧 IDOR 다.
         */
        val ownedIds = wardrobe.mapNotNull { it.id }.toSet()
        val suggested = suggestion.clothesIds.distinct()
        if (suggested.isEmpty() || !ownedIds.containsAll(suggested)) {
            throw AiInvalidResponseException("추천 결과에 소유하지 않은 의류 id 가 있습니다: ${suggested - ownedIds}")
        }

        return coordinationService.create(ownerId, suggestion.title, suggested, suggestion.reason)
    }

    /**
     * 가상 착용 이미지를 만든다.
     *
     * 이미 있으면 생성기를 호출하지 않고 기존 경로를 그대로 돌려준다(멱등).
     * 이미지 생성은 호출당 과금되는 자원이라, 버튼을 두 번 누른 것과 두 장을
     * 만드는 것은 전혀 다른 일이다.
     */
    fun generateTryOn(ownerId: Long, coordinationId: Long): String {
        val coordination = coordinationRepository.findByIdAndOwnerIdWithItems(coordinationId, ownerId)
            ?: throw CoordinationNotFoundException(coordinationId)
        coordination.tryOnImagePath?.let { return it }

        val generator = tryOnProvider.require()
        val user = userRepository.findById(ownerId).orElseThrow { NoBodyPhotoException() }
        val bodyPhotoPath = user.bodyPhotoPath ?: throw NoBodyPhotoException()
        val bodyPhoto = mediaStorage.read(bodyPhotoPath) ?: throw NoBodyPhotoException()

        // 사진이 없는 옷은 건너뛴다. 이름만 있는 옷 때문에 기능 전체를 막을 이유는 없다.
        val itemImages = coordination.items
            .mapNotNull { it.clothes.imagePath }
            .mapNotNull { mediaStorage.read(it) }

        val generated = generator.generate(bodyPhoto, itemImages)
        val stored = mediaStorage.store("tryon", generated, null)
        coordinationRepository.updateTryOnImagePath(coordinationId, ownerId, stored.relativePath)
        return stored.relativePath
    }

    /** 빈이 없으면 = AI 를 쓸 수 없는 환경. 503 으로 나가고 다른 기능은 영향을 받지 않는다. */
    private fun <T : Any> ObjectProvider<T>.require(): T =
        getIfAvailable() ?: throw AiUnavailableException()
}

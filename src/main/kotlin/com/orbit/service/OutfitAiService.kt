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
 * 오늘 만들 수 있는 조합을 이미 다 만들었다. 409 `exhausted` 로 변환된다.
 *
 * [DuplicateCoordinationException] 과 **다른 사건**이다. 중복은 "이번 추천이 하필
 * 겹쳤다"라서 다시 누르면 다른 조합이 나올 수 있지만, 이쪽은 산수로 이미 결론이
 * 난 상태다 — 몇 번을 더 눌러도 새 조합은 나오지 않는다. 사용자가 할 일이
 * "다시 시도"와 "옷을 더 등록하거나 내일 다시"로 갈리므로 응답에서도 갈라야 한다.
 */
class CombinationsExhaustedException(val possible: Long, val used: Long) :
    RuntimeException("오늘 만들 수 있는 조합을 모두 사용했습니다 ($used/$possible)")

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
    private val imageNormalizer: ImageNormalizer,
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
     *
     * **다만 세 겹 전부가 "일단 불러 보고 판정한다"에 서 있다.** 옷이 상의1·하의1
     * 뿐이면 가능한 조합이 산수로 1개인데, 두 번째 추천도 똑같이 AI 를 부르고 409 를
     * 받고 클라이언트가 재시도하기를 반복한 뒤에야 끝난다 — 새 사용자가 가장 먼저
     * 마주치는 상태에서 40초와 세 번의 과금이 확정적으로 낭비된다. 그래서 호출 전에
     * [RecommendableCombinations] 로 먼저 센다. 셈이 "더 나올 것이 없다"고 하면
     * AI 를 부르지 않고 [CombinationsExhaustedException] 으로 즉시 끝낸다.
     *
     * [situation] 은 사용자가 이번 한 번만 적어 주는 오늘의 맥락("비 오고 쌀쌀해")이다.
     * 선택이고, 없으면 예전과 완전히 같은 경로를 탄다. 프롬프트에 실릴 뿐 아니라
     * 코디에 함께 저장된다 — 기록에서 "그때 왜 이걸 입었지"에 답하는 값이라
     * 프롬프트에만 쓰고 버리면 절반만 남는다.
     *
     * 돌아온 응답은 두 번 검사된다. **id 가 내 것인가**(아래)와 **구성이 한 벌인가**
     * ([requireOutfitShape]). 둘 다 프롬프트에 이미 적혀 있는 요구지만, 프롬프트는
     * 요청이지 보장이 아니다.
     */
    fun recommend(ownerId: Long, situation: String? = null): Coordination {
        val recommender = recommenderProvider.require()
        // 공백만 적어 보낸 것은 안 적은 것과 같다. 여기서 한 번 눌러 두면 아래 저장,
        // 프롬프트, 응답이 전부 같은 값을 본다.
        val context = situation?.trim()?.takeIf { it.isNotBlank() }?.take(CoordinationLimits.SITUATION)

        // 옷장에서 치운 옷은 후보에 넣지 않는다. 이미 버린 옷을 오늘 입으라고
        // 추천하는 것만큼 쓸모없는 결과도 없다.
        val wardrobe = clothesRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId)
        val hasTop = wardrobe.any { it.mainCategory == MainCategory.TOP }
        val hasBottom = wardrobe.any { it.mainCategory == MainCategory.BOTTOM }
        if (!hasTop || !hasBottom) throw NotEnoughClothesException()

        val today = coordinationService.todayCoordinations(ownerId)

        // 호출 전 판정. 여기서 끊으면 AI 호출도, 그 뒤의 409 → 재시도 루프도 아예 없다.
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
                // 저장만 하고 쓰지 않으면 설정 화면이 장식이 된다. 이 값이 실제로
                // 프롬프트까지 도달하는지는 테스트로 고정해 뒀다(StylePreferenceTest).
                stylePreference = userRepository.findById(ownerId).orElse(null)?.stylePreference,
                situation = context,
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

        // id 가 전부 내 것이어도 **구성이 코디가 아닐 수 있다.** 아래 참고.
        val byId = wardrobe.associateBy { it.id }
        requireOutfitShape(suggested.mapNotNull { byId[it]?.mainCategory })

        return coordinationService.create(ownerId, suggestion.title, suggested, suggestion.reason, context)
    }

    /**
     * 추천 결과가 **입을 수 있는 한 벌인지** 검사한다. 상의 1 + 하의 1 + 아우터 0또는1.
     *
     * ## 왜 서버가 다시 보는가
     *
     * 프롬프트의 [고르는 규칙] 1번이 이미 같은 말을 하고 있다. 그런데 이 저장소는
     * 중복 조합에서 이미 한 번 배웠다 — **프롬프트는 요청이지 보장이 아니다.**
     * 모델이 상의만 돌려줘도 지금까지는 그대로 저장됐고, 사용자는 바지 없는 LOOK 을
     * 받는다. id 검증만으로는 이걸 못 잡는다. 상의만 고른 응답의 id 는 전부 진짜다.
     *
     * ## 왜 잘라내지 않고 거절하는가
     *
     * 상의가 둘이면 하나를 버리고 저장할 수도 있다. 그렇게 하지 않는 이유는
     * **[com.orbit.ai.OutfitSuggestion.reason] 때문**이다. 이유 문장은 모델이 고른
     * 조합을 설명한 글이라, 서버가 구성을 손대는 순간 "네이비 셔츠에 카디건을
     * 얹었어요"라고 적힌 코디에 카디건이 없는 상태가 된다. 조합과 설명이 어긋난
     * 기록은 조합이 하나 모자란 것보다 나쁘다 — 이 앱에서 사용자가 다시 보는 것은
     * 조합보다 이유 쪽이다.
     *
     * 거절은 [AiInvalidResponseException] → 502 로 나간다. 클라이언트가 보낸 것이
     * 없으므로 400 이 아니고, 사용자가 할 수 있는 일은 재시도뿐이다. 재시도가 실제로
     * 통할 여지도 있다 — 같은 프롬프트라도 다음 응답은 규격을 지킬 수 있다.
     *
     * 아우터가 없는 것은 **정상이다.** 여름에 아우터를 얹는 편이 오히려 틀렸다.
     * 이 규격은 [RecommendableCombinations] 의 조합 수 식과 같은 모양이어야 한다.
     * 둘 중 하나만 고치면 "다 봤다" 판정이 실제와 어긋난다.
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
        /*
         * 결과를 그대로 저장하지 않고 고정 캔버스에 앉힌다. 모델의 출력 크기는
         * 입력 사진을 따라가므로, 전신 사진을 바꾸면 그날 이후의 기록만 모양이
         * 달라진다. 목록은 그 둘을 나란히 세워야 하고, 그러면 줄이 맞지 않는다.
         * 이유는 [MediaProperties.tryonCanvasWidth] 에 적었다.
         */
        val canvased = imageNormalizer.fitToTryonCanvas(generated, ImageType.detect(generated) ?: ImageType.JPEG)
        val stored = mediaStorage.store("tryon", canvased.bytes, canvased.type.mime)
        coordinationRepository.updateTryOnImagePath(coordinationId, ownerId, stored.relativePath)
        return stored.relativePath
    }

    /** 빈이 없으면 = AI 를 쓸 수 없는 환경. 503 으로 나가고 다른 기능은 영향을 받지 않는다. */
    private fun <T : Any> ObjectProvider<T>.require(): T =
        getIfAvailable() ?: throw AiUnavailableException()
}

/**
 * "오늘 더 만들 수 있는 조합이 남아 있는가"를 AI 를 부르기 전에 판정한다.
 *
 * ## 조합 수의 기준
 *
 * ```
 * 가능한 조합 수 = 상의 수 × 하의 수 × (아우터 수 + 1)
 * ```
 *
 * 추천 프롬프트의 [고르는 규칙] 1번이 "상의(TOP) 1벌과 하의(BOTTOM) 1벌은 반드시
 * 고른다. 아우터(OUTER)는 어울릴 때만 1벌 더한다" 이므로, 추천이 만들 수 있는 조합은
 * **상의 1 + 하의 1 + 아우터 0또는1** 뿐이다. 아우터의 `+1` 은 "아우터를 안 입는
 * 경우"를 세는 항이다. 이 식이 프롬프트 규칙과 어긋나면 판정이 틀리므로, 규칙을
 * 고치면 이 식도 같이 고쳐야 한다
 * ([com.orbit.ai.gemini.GeminiOutfitRecommender.buildPrompt] 의 [고르는 규칙]).
 *
 * ## 왜 "이미 쓴 조합"을 그냥 세지 않는가
 *
 * 오늘 만든 코디에는 **수동 생성**이 섞일 수 있고, 수동 생성은 위 규칙을 따르지
 * 않는다(상의 두 벌, 하의만 한 벌 같은 조합도 만들 수 있다). 그런 코디는 추천이
 * 만들 수 있는 조합 공간 안에 있지 않으므로 세면 안 된다 — 세어 버리면 아직 남은
 * 조합이 있는데도 "다 봤다"고 말하게 된다. 그래서 오늘 코디 중 **추천이 만들 수 있는
 * 모양인 것만** 골라 센다. 소프트 삭제된 옷이 낀 과거 조합도 같은 이유로 제외한다
 * (그 옷은 지금 옷장에 없어서 다시 추천될 수 없다).
 *
 * 판정은 이렇게 해서 **한쪽으로만 틀린다.** 셈이 실제보다 적게 나오면 AI 를 한 번 더
 * 부를 뿐이고(그 뒤는 기존 409 duplicate 흐름이 받는다), 반대 방향 — 아직 남았는데
 * 다 썼다고 막는 것 — 은 일어나지 않는다. 사용자에게서 가능한 조합을 빼앗는 쪽이
 * 호출 한 번을 낭비하는 쪽보다 나쁘기 때문에 의도적으로 이 방향을 골랐다.
 */
internal object RecommendableCombinations {

    /** 이 옷장으로 만들 수 있는 조합의 수. 위 주석의 식 그대로다. */
    fun total(wardrobe: List<Clothes>): Long {
        val tops = wardrobe.count { it.mainCategory == MainCategory.TOP }.toLong()
        val bottoms = wardrobe.count { it.mainCategory == MainCategory.BOTTOM }.toLong()
        val outers = wardrobe.count { it.mainCategory == MainCategory.OUTER }.toLong()
        return tops * bottoms * (outers + 1)
    }

    /**
     * 오늘 이미 소비한 조합의 수. 위 조합 공간 **안에 있는** 것만 센다.
     *
     * 중복 판정이 이미 같은 집합의 코디를 막고 있으므로 오늘 코디의 조합은 서로
     * 다르다. 그래도 집합으로 한 번 더 눌러 둔다 — 이 함수가 중복 판정의 구현에
     * 기대지 않게 하려는 것이고, 그래야 셈이 [total] 을 넘지 않는다.
     */
    fun usedToday(wardrobe: List<Clothes>, today: List<Coordination>): Long {
        val inWardrobe = wardrobe.mapNotNull { it.id }.toSet()
        return today.asSequence()
            .filter { it.isRecommendableShape(inWardrobe) }
            .map { it.clothesIdSet() }
            .distinct()
            .count()
            .toLong()
    }

    /** 추천이 만들 수 있는 모양인가 — 지금 옷장에 있는 옷으로 상의1·하의1·아우터0또는1. */
    private fun Coordination.isRecommendableShape(inWardrobe: Set<Long>): Boolean {
        val clothes = items.map { it.clothes }
        if (clothes.any { it.id !in inWardrobe }) return false
        val byCategory = clothes.groupingBy { it.mainCategory }.eachCount()
        return byCategory[MainCategory.TOP] == 1 &&
            byCategory[MainCategory.BOTTOM] == 1 &&
            (byCategory[MainCategory.OUTER] ?: 0) <= 1
    }
}

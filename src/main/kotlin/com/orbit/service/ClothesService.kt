package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.ClothesLimits
import com.orbit.domain.MainCategory
import com.orbit.domain.Seasons
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationItemRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 없거나, 있어도 내 것이 아닌 경우. 두 상황을 하나의 예외로 합친 것은 의도적이다.
 * 자세한 이유는 [ClothesService.findOwned] 주석 참고.
 */
class ClothesNotFoundException(val id: Long) : RuntimeException("의류를 찾을 수 없습니다: $id")

/**
 * 옷의 서술 속성 묶음. 이름·카테고리·사진을 뺀 나머지 전부다.
 *
 * **왜 묶는가.** 속성이 넷 늘면서 `create` 의 인자가 열 개가 됐다. 인자가 열 개면
 * 순서를 한 칸 틀려도 컴파일이 통과한다(전부 `String?` 이다) — 소재 자리에 핏이
 * 들어가도 아무도 모른다. 한 덩어리로 묶으면 호출부가 이름으로 채우게 되고,
 * "빈 문자열은 지운다 / 길이 상한에서 자른다"는 규칙도 [normalized] 한 곳에 모인다.
 *
 * **null 의 뜻은 호출 맥락이 정한다.** 등록에서는 "값이 없다", 수정(PATCH)에서는
 * "건드리지 않는다"이다. 지우고 싶으면 양쪽 다 빈 문자열을 보낸다 — 이 규칙은
 * 원래 color·detail 만의 것이었고, 새 속성도 같은 규칙을 따른다.
 */
data class ClothesTraits(
    val color: String? = null,
    val subCategory: String? = null,
    val material: String? = null,
    val fit: String? = null,
    val season: String? = null,
    val detail: String? = null,
) {
    /**
     * 앞뒤 공백을 털고, 공백만 남으면 null 로, 넘치면 컬럼 길이에서 자른 값.
     *
     * 자르기가 검증과 겹치는 것은 알고 있다(웹 계층의 `@Size` 가 먼저 거른다).
     * 그래도 여기서 한 번 더 자르는 이유는 multipart 등록 경로에 `@Valid` 가 걸리지
     * 않기 때문이다. 검증을 우회한 값이 들어와도 DB 제약 위반(=500)이 아니라
     * 잘린 값으로 끝나야 한다.
     */
    fun normalized(): ClothesTraits = ClothesTraits(
        color = color.clean(ClothesLimits.COLOR),
        subCategory = subCategory.clean(ClothesLimits.SUB_CATEGORY),
        material = material.clean(ClothesLimits.MATERIAL),
        fit = fit.clean(ClothesLimits.FIT),
        // 계절만 표준 표기로 한 번 눌러 준다([Seasons]). 자유 입력 필드지만 추천
        // 프롬프트의 규칙이 이 값을 직접 비교하기 때문에(`季節:夏` ↔ `季節:冬`),
        // 아는 표기가 두 언어로 섞이면 그 비교가 조용히 어긋난다. 목록에 없는 값은
        // 그대로 통과한다 — 사용자가 자기 말로 적는 길까지 막을 이유는 없다.
        season = Seasons.normalize(season.clean(ClothesLimits.SEASON)),
        detail = detail.clean(ClothesLimits.DETAIL),
    )

    private fun String?.clean(max: Int): String? = this?.trim()?.ifEmpty { null }?.take(max)
}

/** 옷장 통계. 화면 하나가 쓰는 값을 한 덩어리로 만든다. */
data class WardrobeStats(
    val total: Long,
    val byCategory: Map<MainCategory, Long>,
    val mostUsed: List<ClothesUsage>,
    val neverUsed: Long,
)

data class ClothesUsage(
    val clothesId: Long,
    val name: String,
    val imagePath: String?,
    val usedCount: Long,
)

/** 통계의 "상위 N개". 화면이 한 줄로 보여주는 개수라 5로 고정한다. */
private const val MOST_USED_LIMIT = 5

@Service
class ClothesService(
    private val clothesRepository: ClothesRepository,
    private val coordinationItemRepository: CoordinationItemRepository,
    private val mediaStorage: MediaStorage,
    private val clock: Clock,
) {

    @Transactional
    fun create(
        ownerId: Long,
        name: String,
        mainCategory: MainCategory,
        traits: ClothesTraits = ClothesTraits(),
        imagePath: String? = null,
    ): Clothes {
        val clean = traits.normalized()
        return clothesRepository.save(
            Clothes(
                ownerId = ownerId,
                name = name.trim().take(ClothesLimits.NAME),
                mainCategory = mainCategory,
                color = clean.color,
                subCategory = clean.subCategory,
                material = clean.material,
                fit = clean.fit,
                season = clean.season,
                detail = clean.detail,
                imagePath = imagePath,
            ),
        )
    }

    /**
     * 목록. 페이지네이션은 서비스가 아니라 호출자가 준 [Pageable] 로 처리하고,
     * 크기 상한은 웹 계층에서 막는다.
     *
     * 원본 Django 에는 `REST_FRAMEWORK.PAGE_SIZE = 20` 설정이 있었지만 뷰가
     * 함수형(`@api_view`)이라 페이지네이션 클래스가 개입할 자리가 없었고, 결국
     * 설정만 있고 한 번도 동작하지 않았다. "설정했다"와 "동작한다"는 다르므로
     * 여기서는 21건을 넣고 1페이지가 20건인지 테스트로 확인한다.
     */
    @Transactional(readOnly = true)
    fun list(ownerId: Long, pageable: Pageable, mainCategory: MainCategory? = null): Page<Clothes> =
        if (mainCategory == null) {
            clothesRepository.findAllByOwnerIdAndDeletedAtIsNullOrderByIdDesc(ownerId, pageable)
        } else {
            clothesRepository.findAllByOwnerIdAndMainCategoryAndDeletedAtIsNullOrderByIdDesc(
                ownerId,
                mainCategory,
                pageable,
            )
        }

    @Transactional(readOnly = true)
    fun get(ownerId: Long, id: Long): Clothes = findOwned(ownerId, id)

    /**
     * PATCH. **보내지 않은(=null) 필드는 건드리지 않고, 빈 문자열은 지운다.**
     *
     * 규칙이 속성마다 달라지면 안 되므로 [traits] 의 모든 필드가 같은 규칙을 따른다.
     * 속성 대부분을 사진 분석이 채우기 때문에 "지우는 길"이 특히 중요하다 — AI 가
     * 잘못 적어 넣은 값을 지울 수 없으면 사용자는 옷을 지우고 다시 등록하는 수밖에 없다.
     *
     * 원본 값(자르기 전)이 아니라 [ClothesTraits.normalized] 를 거친 값을 본다.
     * 그래야 `"   "` 처럼 공백만 보낸 것이 "지우기"로 읽힌다 — 이 판단은 등록 경로와
     * 같아야 하고, 같은 함수를 쓰는 것이 같음을 보장하는 가장 싼 방법이다.
     */
    @Transactional
    fun update(
        ownerId: Long,
        id: Long,
        name: String?,
        mainCategory: MainCategory?,
        traits: ClothesTraits = ClothesTraits(),
    ): Clothes {
        val clothes = findOwned(ownerId, id)
        val clean = traits.normalized()
        name?.let { clothes.name = it.trim().take(ClothesLimits.NAME) }
        mainCategory?.let { clothes.mainCategory = it }
        // `traits.X != null` 이 "이 필드를 보냈다", `clean.X` 가 "무엇으로 바꿀 것인가"다.
        // 빈 문자열은 앞은 참이고 뒤는 null 이라 그대로 "지우기"가 된다.
        if (traits.color != null) clothes.color = clean.color
        if (traits.subCategory != null) clothes.subCategory = clean.subCategory
        if (traits.material != null) clothes.material = clean.material
        if (traits.fit != null) clothes.fit = clean.fit
        if (traits.season != null) clothes.season = clean.season
        if (traits.detail != null) clothes.detail = clean.detail
        return clothes // 더티 체킹으로 반영된다
    }

    /**
     * 옷장 통계.
     *
     * 쿼리 3회로 고정한다 — 카테고리별 개수 1, 많이 입은 옷 1, 쓰인 옷 수 1.
     * "한 번도 안 쓴 옷"은 전체에서 쓰인 옷 수를 빼서 구하므로 쿼리가 늘지 않는다.
     * 옷 목록을 전부 읽어 애플리케이션에서 세는 방법이 제일 쉽지만, 그러면 통계
     * 화면을 열 때마다 옷장 전체가 네트워크와 힙을 지나가게 된다.
     */
    @Transactional(readOnly = true)
    fun stats(ownerId: Long): WardrobeStats {
        val byCategory = clothesRepository.countByCategory(ownerId)
            .associate { (it[0] as MainCategory) to (it[1] as Number).toLong() }
        val total = byCategory.values.sum()

        val mostUsed = coordinationItemRepository
            .findMostUsed(ownerId, PageRequest.of(0, MOST_USED_LIMIT))
            .map {
                ClothesUsage(
                    clothesId = (it[0] as Number).toLong(),
                    name = it[1] as String,
                    imagePath = it[2] as String?,
                    usedCount = (it[3] as Number).toLong(),
                )
            }

        val used = coordinationItemRepository.countUsedClothes(ownerId)
        return WardrobeStats(
            total = total,
            byCategory = byCategory,
            mostUsed = mostUsed,
            neverUsed = total - used,
        )
    }

    /**
     * 삭제. **쓰인 적이 있는지에 따라 물리 삭제와 소프트 삭제로 갈린다.**
     *
     * 이전 구현은 코디에 물린 옷을 409 로 거절했다. 기록이 깨지는 것은 막았지만,
     * "과거 기록을 보는 앱"에서 한 번 입은 옷은 영영 못 지우게 되는 부작용이 있었다.
     * 실제로는 옷을 버리고 나서도 지난 코디는 남아 있어야 하는 게 맞으므로,
     * 요구가 둘로 나뉜다는 것을 인정하고 각각에 답한다.
     *
     *  - 쓰인 적 없음 → 행과 이미지 파일까지 지운다. 참조가 없으니 남길 이유가 없고,
     *    남기면 그대로 고아 파일이 된다.
     *  - 쓰인 적 있음 → [Clothes.deletedAt] 만 찍는다. 옷장·통계·추천 후보에서
     *    사라지지만 과거 코디의 아이템은 그대로 살아 있다. 이미지 파일도 남긴다 —
     *    과거 코디 화면이 여전히 그 사진을 보여주기 때문이다.
     *
     * 어느 쪽이든 응답은 204 로 같다. 사용자가 "왜 어떤 옷은 지워지고 어떤 옷은
     * 안 지워지는가"를 이해해야 할 이유가 없기 때문이다.
     *
     * 이미 지운 옷을 다시 지우려 하면 404 다. [findOwned] 가 `deletedAt is null`
     * 조건을 걸고 있어 옷장에 없는 것과 같이 취급된다.
     */
    @Transactional
    fun delete(ownerId: Long, id: Long) {
        val clothes = findOwned(ownerId, id)
        if (coordinationItemRepository.existsByClothesId(id)) {
            clothes.deletedAt = clock.instant() // 더티 체킹
            return
        }
        val imagePath = clothes.imagePath
        clothesRepository.delete(clothes)
        // 행을 실제로 지운 뒤에 파일을 지운다. 순서를 뒤집었다가 삭제가 롤백되면
        // DB 에는 경로가 있는데 파일이 없는, 화면에서 깨진 이미지로 드러나는 상태가 된다.
        clothesRepository.flush()
        imagePath?.let { mediaStorage.deleteQuietly(it) }
    }

    /**
     * 소유권 격리의 단일 지점.
     *
     * 남의 리소스에 403 이 아니라 404 를 주는 이유: 403 은 "그 id 는 실재한다"는
     * 정보를 흘린다. id 를 1부터 훑으면 남이 옷을 몇 개 가졌는지까지 셀 수 있다.
     * 권한이 없는 사용자에게는 없는 것과 구별되지 않아야 한다. 인증 자체가 없을 때만
     * 401 로 구분한다(그건 존재 여부와 무관한 정보다).
     *
     * 소프트 삭제된 옷도 여기서 404 다. 옷장에서 치운 옷은 사용자 입장에서 없는
     * 옷이고, 행이 남아 있다는 것은 순전히 과거 코디를 지키기 위한 구현 사정이다.
     */
    private fun findOwned(ownerId: Long, id: Long): Clothes =
        clothesRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId) ?: throw ClothesNotFoundException(id)
}

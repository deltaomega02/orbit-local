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

/** 없거나 내 것이 아닌 경우. 둘을 구분하지 않는다([ClothesService.findOwned] 참고). */
class ClothesNotFoundException(val id: Long) : RuntimeException("의류를 찾을 수 없습니다: $id")

/**
 * 옷의 서술 속성(이름·카테고리·사진 제외). 같은 타입 인자가 많아 순서 실수를 막으려고 묶었다.
 * null 은 등록에서 "값 없음", PATCH 에서 "변경 안 함"이고, 지우려면 빈 문자열을 보낸다.
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
     * trim 후 빈 값은 null, 긴 값은 컬럼 길이에서 자른다.
     * multipart 등록 경로에는 `@Valid` 가 걸리지 않아 여기서도 자른다.
     */
    fun normalized(): ClothesTraits = ClothesTraits(
        color = color.clean(ClothesLimits.COLOR),
        subCategory = subCategory.clean(ClothesLimits.SUB_CATEGORY),
        material = material.clean(ClothesLimits.MATERIAL),
        fit = fit.clean(ClothesLimits.FIT),
        // 추천 프롬프트가 계절 값을 직접 비교하므로 아는 표기만 표준값으로 바꾸고, 나머지는 그대로 둔다.
        season = Seasons.normalize(season.clean(ClothesLimits.SEASON)),
        detail = detail.clean(ClothesLimits.DETAIL),
    )

    private fun String?.clean(max: Int): String? = this?.trim()?.ifEmpty { null }?.take(max)
}

/** 옷장 통계 화면용 값. */
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

/** 많이 입은 옷 상위 N개. 화면 한 줄에 보이는 개수. */
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

    /** 목록. 페이지 크기 상한은 웹 계층에서 막는다. */
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
     * PATCH. null 필드는 건드리지 않고 빈 문자열(공백만 포함)은 지운다.
     * 등록과 같은 [ClothesTraits.normalized] 규칙을 쓴다.
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
        // traits.X != null 이면 보낸 필드. 빈 문자열은 clean.X 가 null 이라 지우기가 된다.
        if (traits.color != null) clothes.color = clean.color
        if (traits.subCategory != null) clothes.subCategory = clean.subCategory
        if (traits.material != null) clothes.material = clean.material
        if (traits.fit != null) clothes.fit = clean.fit
        if (traits.season != null) clothes.season = clean.season
        if (traits.detail != null) clothes.detail = clean.detail
        return clothes // 더티 체킹으로 반영된다
    }

    /** 옷장 통계. 옷 전체를 읽지 않고 집계 쿼리 3회로 구한다. */
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
     * 코디에 쓰인 적 없는 옷은 행과 이미지까지 지우고, 쓰인 옷은 과거 코디를 위해
     * [Clothes.deletedAt] 만 찍는다(이미지 유지). 이미 지운 옷은 404.
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
        // 삭제가 롤백돼 파일만 사라지는 일이 없도록 flush 뒤에 파일을 지운다.
        clothesRepository.flush()
        imagePath?.let { mediaStorage.deleteQuietly(it) }
    }

    /**
     * 소유권 확인 지점. id 존재 여부가 드러나지 않도록 남의 옷에도 403 이 아니라 404 를 준다.
     * 소프트 삭제된 옷도 404.
     */
    private fun findOwned(ownerId: Long, id: Long): Clothes =
        clothesRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId) ?: throw ClothesNotFoundException(id)
}

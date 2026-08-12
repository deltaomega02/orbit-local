package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
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
        color: String?,
        imagePath: String? = null,
        detail: String? = null,
    ): Clothes =
        clothesRepository.save(
            Clothes(
                ownerId = ownerId,
                name = name.trim(),
                mainCategory = mainCategory,
                color = color?.trim()?.ifEmpty { null },
                imagePath = imagePath,
                detail = detail?.trim()?.ifEmpty { null }?.take(200),
            ),
        )

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

    @Transactional
    fun update(ownerId: Long, id: Long, name: String?, mainCategory: MainCategory?, color: String?): Clothes {
        val clothes = findOwned(ownerId, id)
        // PATCH 이므로 null 은 "변경 없음"이다. 색을 지우려면 빈 문자열을 보낸다.
        name?.let { clothes.name = it.trim() }
        mainCategory?.let { clothes.mainCategory = it }
        color?.let { clothes.color = it.trim().ifEmpty { null } }
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

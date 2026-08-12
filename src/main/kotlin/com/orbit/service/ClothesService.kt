package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationItemRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 없거나, 있어도 내 것이 아닌 경우. 두 상황을 하나의 예외로 합친 것은 의도적이다.
 * 자세한 이유는 [ClothesService.findOwned] 주석 참고.
 */
class ClothesNotFoundException(val id: Long) : RuntimeException("의류를 찾을 수 없습니다: $id")

/** 코디에 물려 있는 의류는 지울 수 없다. */
class ClothesInUseException(val id: Long) : RuntimeException("코디에 사용 중인 의류입니다: $id")

@Service
class ClothesService(
    private val clothesRepository: ClothesRepository,
    private val coordinationItemRepository: CoordinationItemRepository,
) {

    @Transactional
    fun create(ownerId: Long, name: String, mainCategory: MainCategory, color: String?): Clothes =
        clothesRepository.save(
            Clothes(ownerId = ownerId, name = name.trim(), mainCategory = mainCategory, color = color?.trim()),
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
    fun list(ownerId: Long, pageable: Pageable): Page<Clothes> =
        clothesRepository.findAllByOwnerIdOrderByIdDesc(ownerId, pageable)

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
     * 삭제. 코디가 참조 중이면 409 로 거절한다.
     *
     * 대안은 코디까지 연쇄 삭제하는 것이지만, 옷 한 벌을 지웠더니 지난 코디 기록이
     * 사라지는 건 사용자가 예상하지 못하는 동작이다. 막고 이유를 알려주는 쪽을 택했다.
     */
    @Transactional
    fun delete(ownerId: Long, id: Long) {
        val clothes = findOwned(ownerId, id)
        if (coordinationItemRepository.existsByClothesId(id)) throw ClothesInUseException(id)
        clothesRepository.delete(clothes)
    }

    /**
     * 소유권 격리의 단일 지점.
     *
     * 남의 리소스에 403 이 아니라 404 를 주는 이유: 403 은 "그 id 는 실재한다"는
     * 정보를 흘린다. id 를 1부터 훑으면 남이 옷을 몇 개 가졌는지까지 셀 수 있다.
     * 권한이 없는 사용자에게는 없는 것과 구별되지 않아야 한다. 인증 자체가 없을 때만
     * 401 로 구분한다(그건 존재 여부와 무관한 정보다).
     */
    private fun findOwned(ownerId: Long, id: Long): Clothes =
        clothesRepository.findByIdAndOwnerId(id, ownerId) ?: throw ClothesNotFoundException(id)
}

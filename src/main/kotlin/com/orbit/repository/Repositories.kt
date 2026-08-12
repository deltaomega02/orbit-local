package com.orbit.repository

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.domain.CoordinationItem
import com.orbit.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean

    /** `/media/…` 소유권 검사용. "이 경로가 이 사용자의 전신 사진인가". */
    fun existsByIdAndBodyPhotoPath(id: Long, bodyPhotoPath: String): Boolean
}

/**
 * 소유자 조건이 없는 조회 메서드는 두지 않는다.
 *
 * 원본 Django 에서는 `Clothes.objects.get(pk=...)` 같은 조회에 `user=request.user`
 * 조건을 뷰마다 손으로 붙이다 몇 군데에서 빠졌고, 그게 곧 IDOR 였다. 여기서는
 * 리포지토리 시그니처가 소유자를 요구하게 만들어 빼먹으면 컴파일이 안 되게 한다.
 */
interface ClothesRepository : JpaRepository<Clothes, Long> {
    fun findAllByOwnerId(ownerId: Long): List<Clothes>
    fun findAllByIdInAndOwnerId(ids: Collection<Long>, ownerId: Long): List<Clothes>

    /** 목록은 페이지 단위로만 노출한다. 최신 등록 순. */
    fun findAllByOwnerIdOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Clothes>

    /**
     * 카테고리 필터. 필터링을 클라이언트에 맡기면 "이미 받아온 페이지" 안에서만
     * 걸러지므로, 옷이 한 페이지를 넘는 순간 결과가 사실과 달라진다.
     * 걸러내는 일은 데이터를 가진 쪽에서 해야 한다.
     */
    fun findAllByOwnerIdAndMainCategoryOrderByIdDesc(
        ownerId: Long,
        mainCategory: MainCategory,
        pageable: Pageable,
    ): Page<Clothes>

    fun findByIdAndOwnerId(id: Long, ownerId: Long): Clothes?

    /** `/media/…` 소유권 검사용. */
    fun existsByOwnerIdAndImagePath(ownerId: Long, imagePath: String): Boolean
}

interface CoordinationItemRepository : JpaRepository<CoordinationItem, Long> {
    /** 코디에 물려 있는 의류인지. 삭제를 409 로 거절하기 위한 조회다. */
    fun existsByClothesId(clothesId: Long): Boolean
}

interface CoordinationRepository : JpaRepository<Coordination, Long> {

    /**
     * 특정 기간에 생성된 코디를 아이템·의류까지 한 번에 가져온다.
     *
     * Django 버전에서는 목록 조회에 prefetch 를 빠뜨려 N+1 이 났었다. 여기서는
     * fetch join 으로 고정하고, 그 사실을 테스트로 검증한다(N1PreventionTest).
     * 컬렉션 fetch join 이 하나뿐이라 카티션 곱 문제는 없다.
     */
    @Query(
        """
        select distinct c from Coordination c
        left join fetch c.mutableItems i
        left join fetch i.clothes
        where c.ownerId = :ownerId
          and c.createdAt >= :from
          and c.createdAt < :to
        """,
    )
    fun findCreatedBetweenWithItems(
        @Param("ownerId") ownerId: Long,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Coordination>

    /**
     * 코디 하나를 아이템·의류까지 붙여서 가져온다.
     *
     * 가상 착용은 각 의류의 이미지 파일을 읽어야 해서 아이템이 반드시 필요하다.
     * 그런데 AI 호출을 트랜잭션 안에서 기다리게 두면 커넥션을 수 초 동안 붙잡게
     * 되므로, 트랜잭션 밖에서 다루려면 컬렉션이 미리 초기화돼 있어야 한다.
     */
    @Query(
        """
        select c from Coordination c
        left join fetch c.mutableItems i
        left join fetch i.clothes
        where c.id = :id and c.ownerId = :ownerId
        """,
    )
    fun findByIdAndOwnerIdWithItems(@Param("id") id: Long, @Param("ownerId") ownerId: Long): Coordination?

    /** `/media/…` 소유권 검사용. */
    fun existsByOwnerIdAndTryOnImagePath(ownerId: Long, tryOnImagePath: String): Boolean

    /**
     * 가상 착용 이미지 경로만 갱신한다.
     *
     * 트랜잭션 밖에서 읽어 온 detached 엔티티를 `save()` 로 merge 하면 아이템
     * 컬렉션까지 통째로 병합 대상이 된다(cascade + orphanRemoval). 바꾸려는 건
     * 컬럼 하나뿐이므로 그 한 컬럼만 건드린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Coordination c set c.tryOnImagePath = :path where c.id = :id and c.ownerId = :ownerId")
    fun updateTryOnImagePath(
        @Param("id") id: Long,
        @Param("ownerId") ownerId: Long,
        @Param("path") path: String,
    ): Int
}

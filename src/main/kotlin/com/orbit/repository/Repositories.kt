package com.orbit.repository

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.domain.CoordinationItem
import com.orbit.domain.LookCounter
import com.orbit.domain.User
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean

    /** `/media` 소유권 검사용. */
    fun existsByIdAndBodyPhotoPath(id: Long, bodyPhotoPath: String): Boolean
}

/** IDOR 방지를 위해 조회 메서드는 모두 소유자 조건을 받는다. */
interface ClothesRepository : JpaRepository<Clothes, Long> {

    // 소프트 삭제 조건 누락을 막기 위해 DeletedAtIsNull 을 메서드 이름에 넣는다

    fun findAllByOwnerIdAndDeletedAtIsNull(ownerId: Long): List<Clothes>

    fun findAllByIdInAndOwnerIdAndDeletedAtIsNull(ids: Collection<Long>, ownerId: Long): List<Clothes>

    fun findAllByOwnerIdAndDeletedAtIsNullOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Clothes>

    fun findAllByOwnerIdAndMainCategoryAndDeletedAtIsNullOrderByIdDesc(
        ownerId: Long,
        mainCategory: MainCategory,
        pageable: Pageable,
    ): Page<Clothes>

    fun findByIdAndOwnerIdAndDeletedAtIsNull(id: Long, ownerId: Long): Clothes?

    /** `/media` 소유권 검사용. 소프트 삭제된 옷의 사진도 과거 코디에서 보여야 하므로 deletedAt 을 보지 않는다. */
    fun existsByOwnerIdAndImagePath(ownerId: Long, imagePath: String): Boolean

    /** 계정 사용 여부 판단용([com.orbit.service.OwnerAccountService]). 소프트 삭제된 옷도 센다. */
    fun countByOwnerId(ownerId: Long): Long

    /** [com.orbit.service.SeasonBackfill] 용. 전체 테이블의 표기를 통일하므로 소유자 조건이 없다(읽기 없음). */
    @Modifying(clearAutomatically = true)
    @Query("update Clothes c set c.season = :to where c.season = :from")
    fun renameSeason(@Param("from") from: String, @Param("to") to: String): Int

    @Query(
        """
        select c.mainCategory, count(c) from Clothes c
        where c.ownerId = :ownerId and c.deletedAt is null
        group by c.mainCategory
        """,
    )
    fun countByCategory(@Param("ownerId") ownerId: Long): List<Array<Any>>
}

interface LookCounterRepository : JpaRepository<LookCounter, Long> {

    /**
     * `select ... for update`. 연속 생성 시 제약 위반·롤백 대신 기다렸다가 다음 번호를 받게 한다.
     * 락 범위는 해당 사용자의 카운터 행뿐이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LookCounter c where c.ownerId = :ownerId")
    fun findForUpdate(@Param("ownerId") ownerId: Long): LookCounter?
}

interface CoordinationItemRepository : JpaRepository<CoordinationItem, Long> {
    /** 물리 삭제와 소프트 삭제를 가르는 기준. */
    fun existsByClothesId(clothesId: Long): Boolean

    /** 쓰인 코디 수 내림차순. 개수 제한은 DB 에서 자르도록 [Pageable] 로 받고, 동점은 id 순. */
    @Query(
        """
        select cl.id, cl.name, cl.imagePath, count(i)
        from CoordinationItem i join i.clothes cl
        where cl.ownerId = :ownerId and cl.deletedAt is null
        group by cl.id, cl.name, cl.imagePath
        order by count(i) desc, cl.id asc
        """,
    )
    fun findMostUsed(@Param("ownerId") ownerId: Long, pageable: Pageable): List<Array<Any>>

    /** 코디에 쓰인 옷 수. 안 쓴 옷 수는 전체에서 이 값을 뺀다. */
    @Query(
        """
        select count(distinct cl.id)
        from CoordinationItem i join i.clothes cl
        where cl.ownerId = :ownerId and cl.deletedAt is null
        """,
    )
    fun countUsedClothes(@Param("ownerId") ownerId: Long): Long
}

interface CoordinationRepository : JpaRepository<Coordination, Long> {

    /** 기간 내 코디를 아이템·의류까지 fetch join 한다(N1PreventionTest 로 검증). */
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

    /** 가상 착용은 트랜잭션 밖에서 AI 를 호출하므로 아이템을 미리 초기화해 가져온다. */
    @Query(
        """
        select c from Coordination c
        left join fetch c.mutableItems i
        left join fetch i.clothes
        where c.id = :id and c.ownerId = :ownerId
        """,
    )
    fun findByIdAndOwnerIdWithItems(@Param("id") id: Long, @Param("ownerId") ownerId: Long): Coordination?

    /** 계정 사용 여부 판단용([com.orbit.service.OwnerAccountService]). */
    fun countByOwnerId(ownerId: Long): Long

    // 컬렉션 fetch join 에 페이지를 걸면 Hibernate 가 메모리에서 자르므로(HHH000104) 두 단계로 나눈다.
    // 1) id 만 페이지 조회 2) 그 id 들을 fetch join. 쿼리는 count 포함 항상 3회.

    @Query(
        value = """
        select c.id from Coordination c
        where c.ownerId = :ownerId
        order by c.createdAt desc, c.id desc
        """,
        countQuery = "select count(c) from Coordination c where c.ownerId = :ownerId",
    )
    fun findIdPageByOwnerId(@Param("ownerId") ownerId: Long, pageable: Pageable): Page<Long>

    /** `uq_coordination_clothes` 로 코디당 최대 한 행만 매칭되므로 distinct 가 필요 없다. */
    @Query(
        value = """
        select c.id from Coordination c join c.mutableItems i
        where c.ownerId = :ownerId and i.clothes.id = :clothesId
        order by c.createdAt desc, c.id desc
        """,
        countQuery = """
        select count(c) from Coordination c join c.mutableItems i
        where c.ownerId = :ownerId and i.clothes.id = :clothesId
        """,
    )
    fun findIdPageByClothesId(
        @Param("ownerId") ownerId: Long,
        @Param("clothesId") clothesId: Long,
        pageable: Pageable,
    ): Page<Long>

    /** 2단계. 소유자 조건 없는 조회를 두지 않기 위해 ownerId 를 다시 받는다. */
    @Query(
        """
        select distinct c from Coordination c
        left join fetch c.mutableItems i
        left join fetch i.clothes
        where c.ownerId = :ownerId and c.id in :ids
        order by c.createdAt desc, c.id desc
        """,
    )
    fun findAllByIdsWithItems(
        @Param("ownerId") ownerId: Long,
        @Param("ids") ids: Collection<Long>,
    ): List<Coordination>

    /** 아이템이 필요 없는 작업(즐겨찾기 토글)용. */
    fun findByIdAndOwnerId(id: Long, ownerId: Long): Coordination?

    /** `/media` 소유권 검사용. */
    fun existsByOwnerIdAndTryOnImagePath(ownerId: Long, tryOnImagePath: String): Boolean

    // --- LOOK 번호

    /**
     * 없으면 null. 카운터가 실제보다 뒤처졌을 때 번호가 겹쳐 생성이 계속 실패하지 않도록
     * 발급 시 [LookCounter] 값과 비교해 큰 쪽을 쓴다.
     */
    @Query("select max(c.lookNo) from Coordination c where c.ownerId = :ownerId")
    fun findMaxLookNo(@Param("ownerId") ownerId: Long): Int?

    @Query("select distinct c.ownerId from Coordination c where c.lookNo is null")
    fun findOwnerIdsWithoutLookNo(): List<Long>

    /** 생성 시각 순(동률은 id). 재실행해도 같은 번호가 매겨지도록 정렬을 고정한다. */
    @Query(
        """
        select c from Coordination c
        where c.ownerId = :ownerId and c.lookNo is null
        order by c.createdAt asc, c.id asc
        """,
    )
    fun findWithoutLookNo(@Param("ownerId") ownerId: Long): List<Coordination>

    /** detached 엔티티를 save() 로 merge 하면 아이템 컬렉션까지 병합되므로 컬럼 하나만 갱신한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Coordination c set c.tryOnImagePath = :path where c.id = :id and c.ownerId = :ownerId")
    fun updateTryOnImagePath(
        @Param("id") id: Long,
        @Param("ownerId") ownerId: Long,
        @Param("path") path: String,
    ): Int
}

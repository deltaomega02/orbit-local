package com.orbit.repository

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.CoordinationItem
import com.orbit.domain.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
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

    fun findByIdAndOwnerId(id: Long, ownerId: Long): Clothes?
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
}

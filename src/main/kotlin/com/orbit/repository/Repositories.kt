package com.orbit.repository

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ClothesRepository : JpaRepository<Clothes, Long> {
    fun findAllByOwnerId(ownerId: Long): List<Clothes>
    fun findAllByIdInAndOwnerId(ids: Collection<Long>, ownerId: Long): List<Clothes>
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

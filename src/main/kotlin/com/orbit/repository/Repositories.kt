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

    /*
     * 소유자 조건과 함께 `DeletedAtIsNull` 도 메서드 이름에 박아 둔다.
     * 소프트 삭제는 조건 하나를 빠뜨리는 순간 "지운 옷이 되살아나는" 버그가 되는데,
     * 그 조건이 서비스 코드에 흩어져 있으면 언젠가 한 곳이 빠진다. 이름에 넣어 두면
     * 조건 없는 조회를 부르려다 메서드가 없어서 멈추게 된다.
     * (조건을 일부러 빼는 자리는 딱 하나, `/media` 소유권 검사다 — 아래 참고)
     */

    fun findAllByOwnerIdAndDeletedAtIsNull(ownerId: Long): List<Clothes>

    fun findAllByIdInAndOwnerIdAndDeletedAtIsNull(ids: Collection<Long>, ownerId: Long): List<Clothes>

    /** 목록은 페이지 단위로만 노출한다. 최신 등록 순. */
    fun findAllByOwnerIdAndDeletedAtIsNullOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Clothes>

    /**
     * 카테고리 필터. 필터링을 클라이언트에 맡기면 "이미 받아온 페이지" 안에서만
     * 걸러지므로, 옷이 한 페이지를 넘는 순간 결과가 사실과 달라진다.
     * 걸러내는 일은 데이터를 가진 쪽에서 해야 한다.
     */
    fun findAllByOwnerIdAndMainCategoryAndDeletedAtIsNullOrderByIdDesc(
        ownerId: Long,
        mainCategory: MainCategory,
        pageable: Pageable,
    ): Page<Clothes>

    fun findByIdAndOwnerIdAndDeletedAtIsNull(id: Long, ownerId: Long): Clothes?

    /**
     * `/media/…` 소유권 검사용. **여기만 `deletedAt` 을 보지 않는다.**
     * 옷장에서 치운 옷도 과거 코디 화면에는 그대로 나오고, 그 사진이 404 가 되면
     * 기록이 반쪽이 된다. "옷장에 있는가"와 "이 파일을 볼 권한이 있는가"는 다른 질문이다.
     */
    fun existsByOwnerIdAndImagePath(ownerId: Long, imagePath: String): Boolean

    /**
     * 계정 이어받기 판단용([com.orbit.service.OwnerAccountService]).
     *
     * **여기도 `deletedAt` 을 보지 않는다.** 묻는 것이 "지금 옷장에 몇 벌 있는가"가
     * 아니라 "이 계정을 실제로 썼는가"이기 때문이다. 옷을 전부 치운 계정도 쓴
     * 계정이고, 갓 만들어진 빈 계정과는 다르게 취급해야 한다.
     */
    fun countByOwnerId(ownerId: Long): Long

    /**
     * 계절 표기 마이그레이션용([com.orbit.service.SeasonBackfill]).
     *
     * **여기만 소유자 조건이 없다.** 표기를 바꾸는 일은 특정 사용자의 데이터를
     * 읽거나 보여주는 일이 아니라 전체 테이블의 표기를 통일하는 일이고, 사용자별로
     * 나눠 돌면 도중에 끊겼을 때 두 언어가 섞인 상태가 남는다. 값을 비교해 값을
     * 바꿀 뿐이라 이 메서드로는 남의 데이터를 읽어낼 수 없다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Clothes c set c.season = :to where c.season = :from")
    fun renameSeason(@Param("from") from: String, @Param("to") to: String): Int

    /** 통계용. 카테고리별 개수를 한 쿼리로 센다 — 카테고리마다 따로 세면 3번 돈다. */
    @Query(
        """
        select c.mainCategory, count(c) from Clothes c
        where c.ownerId = :ownerId and c.deletedAt is null
        group by c.mainCategory
        """,
    )
    fun countByCategory(@Param("ownerId") ownerId: Long): List<Array<Any>>
}

/**
 * LOOK 번호 발급 이력([LookCounter]).
 *
 * 조회 메서드가 하나뿐이고 그것도 쓰기 락을 건다. 이 행은 "읽어서 보여주는" 값이
 * 아니라 "읽고 곧바로 늘리는" 값이라, 락 없이 읽을 이유가 없기 때문이다.
 */
interface LookCounterRepository : JpaRepository<LookCounter, Long> {

    /**
     * 번호를 발급하려고 행을 잠근 채 읽는다(`select ... for update`).
     *
     * 낙관적 재시도만으로도 유니크 제약이 겹침을 막아 주지만, 그건 "겹치면 실패시킨다"
     * 이지 "겹치지 않게 한다"가 아니다. 짧은 간격의 연속 생성(추천 → 409 → 재시도)에서
     * 매번 제약 위반과 롤백을 거치게 두면, 정상 흐름이 예외 경로를 타게 된다.
     * 행 락을 걸면 두 번째 요청은 실패하는 대신 **기다렸다가** 다음 번호를 받는다.
     * 제약과 재시도는 그 뒤를 받치는 안전망으로 남는다.
     *
     * 락 범위가 사용자 한 명의 카운터 행이라, 다른 사용자의 생성은 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LookCounter c where c.ownerId = :ownerId")
    fun findForUpdate(@Param("ownerId") ownerId: Long): LookCounter?
}

interface CoordinationItemRepository : JpaRepository<CoordinationItem, Long> {
    /** 코디에 물려 있는 의류인지. 물리 삭제와 소프트 삭제를 가르는 기준이다. */
    fun existsByClothesId(clothesId: Long): Boolean

    /**
     * 많이 입은 옷. 옷 하나당 쓰인 코디 수를 세서 내림차순으로 준다.
     *
     * 개수 제한을 [Pageable] 로 받는 이유: 애플리케이션에서 전부 읽고 자르면 옷이
     * 늘어날수록 읽는 양이 함께 늘고, "상위 5개"를 보려고 옷장 전체를 옮기는 꼴이 된다.
     * 동점일 때 순서가 흔들리지 않도록 id 를 2차 정렬 키로 둔다.
     */
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

    /**
     * 한 번이라도 코디에 쓰인 옷의 수. "한 번도 안 쓴 옷"은 전체에서 이 값을 뺀다.
     * 반대로(쓰이지 않은 옷을 직접 세기) 하려면 not exists 서브쿼리가 필요한데,
     * 빼기 한 번이면 되는 것을 굳이 어렵게 물을 이유가 없다.
     */
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

    /** 계정 이어받기 판단용([com.orbit.service.OwnerAccountService]). 이 계정이 쌓은 코디 수. */
    fun countByOwnerId(ownerId: Long): Long

    /*
     * ── 기록 목록: 컬렉션 fetch join 과 페이지네이션을 어떻게 같이 쓰는가 ──
     *
     * 컬렉션을 fetch join 한 쿼리에 limit/offset 을 붙이면 JPA 는 자를 수 없다.
     * 조인 결과의 한 행은 코디가 아니라 "코디 × 아이템"이라, DB 에서 20행을 자르면
     * 코디가 20개 나오는 게 아니라 중간에서 잘린 코디가 나온다. Hibernate 는 이걸
     * 알기 때문에 전부 읽어 메모리에서 자르고(HHH000104) 경고만 남긴다 — 즉
     * 페이지네이션이 조용히 사라진다. 원본에서 목록이 항상 전체를 반환했던 것과
     * 사실상 같은 상태가 된다.
     *
     * 그래서 두 단계로 나눈다.
     *  1) id 만 페이지네이션해서 가져온다 (컬렉션이 없으니 DB 가 정확히 자른다)
     *  2) 그 id 들의 코디를 아이템·의류까지 한 번에 fetch join 한다
     * 쿼리는 count + id + fetch 로 항상 3회다. 코디가 몇 건이든 늘지 않는다.
     */

    @Query(
        value = """
        select c.id from Coordination c
        where c.ownerId = :ownerId
        order by c.createdAt desc, c.id desc
        """,
        countQuery = "select count(c) from Coordination c where c.ownerId = :ownerId",
    )
    fun findIdPageByOwnerId(@Param("ownerId") ownerId: Long, pageable: Pageable): Page<Long>

    /**
     * 이 옷이 실제로 쓰인 코디의 id. `uq_coordination_clothes` 덕분에 코디 하나당
     * 아이템이 최대 하나만 매칭되므로 distinct 가 필요 없다.
     */
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

    /**
     * 2단계. id 목록으로 아이템·의류까지 한 번에 읽는다.
     *
     * id 가 이미 소유자 조건을 통과한 값이지만 `ownerId` 를 다시 받는다.
     * 이 리포지토리에 소유자 조건 없는 조회를 하나라도 두면, 다음에 누군가
     * "id 만 알면 되는" 자리에서 그걸 집어 쓰기 때문이다.
     */
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

    /**
     * 아이템이 필요 없는 작업용(즐겨찾기 토글). 아이템까지 끌고 오면 쓰지도 않을
     * 조인이 붙고, 더티 체킹 대상 컬렉션만 늘어난다.
     */
    fun findByIdAndOwnerId(id: Long, ownerId: Long): Coordination?

    /** `/media/…` 소유권 검사용. */
    fun existsByOwnerIdAndTryOnImagePath(ownerId: Long, tryOnImagePath: String): Boolean

    /*
     * ── LOOK 번호 관련 ──
     */

    /**
     * 이 사용자의 코디 중 가장 큰 LOOK 번호. 하나도 없으면 null.
     *
     * 번호 발급의 기준값은 [LookCounter] 지만, 발급 직전에 이 값과 큰 쪽을 취한다.
     * 카운터가 어떤 이유로든 실제보다 뒤처져 있으면(마이그레이션이 도중에 끊겼다든가,
     * 누군가 DB 에 직접 행을 넣었다든가) 이미 쓰인 번호를 다시 발급하게 되는데,
     * 그건 유니크 제약에 걸려 생성 자체가 실패하는 상태가 된다 — 재시도해도 계속.
     * 큰 쪽을 취하면 그런 어긋남이 다음 발급 한 번으로 저절로 복구된다.
     */
    @Query("select max(c.lookNo) from Coordination c where c.ownerId = :ownerId")
    fun findMaxLookNo(@Param("ownerId") ownerId: Long): Int?

    /** 마이그레이션 대상. LOOK 번호가 없는 행을 가진 사용자들. */
    @Query("select distinct c.ownerId from Coordination c where c.lookNo is null")
    fun findOwnerIdsWithoutLookNo(): List<Long>

    /**
     * 번호가 없는 코디를 **생성 시각 순**으로. 화면이 "먼저 만든 코디가 더 작은 번호"를
     * 뜻하는 값으로 이 번호를 읽으므로, 소급해서 매기는 번호도 그 순서를 따라야 한다.
     * 같은 시각이면 id 로 가른다 — 정렬이 흔들리면 마이그레이션을 다시 돌렸을 때
     * 번호가 달라진다.
     */
    @Query(
        """
        select c from Coordination c
        where c.ownerId = :ownerId and c.lookNo is null
        order by c.createdAt asc, c.id asc
        """,
    )
    fun findWithoutLookNo(@Param("ownerId") ownerId: Long): List<Coordination>

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

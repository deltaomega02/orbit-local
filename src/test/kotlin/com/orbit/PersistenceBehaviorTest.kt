package com.orbit

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.service.CoordinationService
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val OWNER = 42L

/**
 * 트랜잭션 경계와 N+1 회귀 테스트.
 * 아이템 저장이 실패하면 코디도 남지 않고, 목록 쿼리 수는 코디 수에 비례해 늘지 않는다.
 */
@SpringBootTest
@DisplayName("영속성 계층 — 트랜잭션 경계와 N+1")
class PersistenceBehaviorTest {

    @Autowired lateinit var service: CoordinationService
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var entityManager: EntityManager

    private lateinit var wardrobe: List<Clothes>

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        wardrobe = listOf(
            Clothes(OWNER, "셔츠", MainCategory.TOP),
            Clothes(OWNER, "슬랙스", MainCategory.BOTTOM),
            Clothes(OWNER, "코트", MainCategory.OUTER),
            Clothes(OWNER, "티셔츠", MainCategory.TOP),
            Clothes(OWNER, "청바지", MainCategory.BOTTOM),
        ).map { clothesRepository.save(it) }
    }

    private fun statistics(): Statistics =
        entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @Test
    fun `아이템 저장이 실패하면 코디도 롤백된다`() {
        val clothes = wardrobe.first()

        // 같은 의류를 한 코디에 두 번 넣어 uq_coordination_clothes 제약을 위반시킨다
        try {
            transactionTemplate.execute {
                val c = Coordination(OWNER, "깨질 코디")
                c.addItem(clothes, 0)
                c.addItem(clothes, 1)
                coordinationRepository.saveAndFlush(c)
            }
            fail("unique 제약 위반이 발생해야 한다")
        } catch (expected: Exception) {
            // 제약 위반으로 트랜잭션이 롤백된다
        }

        assertEquals(
            0, coordinationRepository.count(),
            "아이템 저장이 실패했는데 코디만 남으면 안 된다",
        )
    }

    @Test
    fun `오늘의 코디 목록은 코디 수와 무관하게 쿼리 1회로 조회된다`() {
        service.create(OWNER, "코디1", listOf(wardrobe[0].id!!, wardrobe[1].id!!))
        service.create(OWNER, "코디2", listOf(wardrobe[3].id!!, wardrobe[4].id!!))
        service.create(OWNER, "코디3", listOf(wardrobe[0].id!!, wardrobe[1].id!!, wardrobe[2].id!!))

        val stats = statistics()
        stats.clear()

        val result = service.todayCoordinations(OWNER)
        // 트랜잭션 밖에서 연관 엔티티에 접근해 fetch join 누락을 드러낸다
        val names = result.flatMap { c -> c.items.map { it.clothes.name } }

        assertEquals(3, result.size)
        assertEquals(7, names.size)
        assertEquals(
            1, stats.prepareStatementCount,
            "코디 3건 + 아이템 7건을 읽는 데 실행된 쿼리 수",
        )
    }

    /**
     * fetch join 과 limit 을 섞으면 메모리에서 잘리므로(HHH000104) id 조회와 fetch 를 나눠 3회로 고정한다.
     * 3건과 10건일 때를 같이 재서 쿼리 수가 늘지 않는지 본다.
     * 첫 페이지가 다 차지 않으면 Spring Data 가 count 를 생략하므로 페이지 크기를 2 로 둔다.
     */
    @Test
    fun `기록 목록은 코디 수와 무관하게 쿼리 3회로 조회된다`() {
        repeat(3) { i ->
            val extraTop = clothesRepository.save(Clothes(OWNER, "추가 상의 $i", MainCategory.TOP))
            service.create(OWNER, "코디 $i", listOf(extraTop.id!!, wardrobe[1].id!!))
        }

        val stats = statistics()
        stats.clear()

        val page = service.history(OWNER, PageRequest.of(0, 2))
        // 트랜잭션 밖에서 연관 엔티티에 접근해 fetch join 누락을 드러낸다
        val names = page.content.flatMap { c -> c.items.map { it.clothes.name } }

        assertEquals(2, page.content.size)
        assertEquals(3, page.totalElements.toInt())
        assertEquals(4, names.size)
        val queriesForThree = stats.prepareStatementCount
        assertEquals(3, queriesForThree, "count + id 페이지 + fetch join = 3회")

        repeat(7) { i ->
            val extraTop = clothesRepository.save(Clothes(OWNER, "더 추가한 상의 $i", MainCategory.TOP))
            service.create(OWNER, "코디 추가 $i", listOf(extraTop.id!!, wardrobe[1].id!!))
        }
        stats.clear()

        val bigger = service.history(OWNER, PageRequest.of(0, 2))
        bigger.content.flatMap { c -> c.items.map { it.clothes.name } }

        assertEquals(10, bigger.totalElements.toInt())
        assertEquals(
            queriesForThree, stats.prepareStatementCount,
            "코디가 3건에서 10건이 됐는데 쿼리가 늘었다면 N+1 이다",
        )
    }

    @Test
    fun `옷별 코디 역참조도 쿼리 3회로 조회된다`() {
        val bottom = wardrobe[1]
        repeat(4) { i ->
            val extraTop = clothesRepository.save(Clothes(OWNER, "역참조용 상의 $i", MainCategory.TOP))
            service.create(OWNER, "코디 $i", listOf(extraTop.id!!, bottom.id!!))
        }

        val stats = statistics()
        stats.clear()

        val page = service.byClothes(OWNER, bottom.id!!, PageRequest.of(0, 2))
        val names = page.content.flatMap { c -> c.items.map { it.clothes.name } }

        assertEquals(2, page.content.size)
        assertEquals(4, page.totalElements.toInt())
        assertEquals(4, names.size)
        assertEquals(3, stats.prepareStatementCount, "count + id 페이지 + fetch join = 3회")
    }

    /**
     * 결과가 비면 fetch 를 건너뛰고 Spring Data 도 count 를 생략해 id 조회 1회로 끝난다.
     * 코디가 없는 사용자가 앱을 처음 열 때의 경로다.
     */
    @Test
    fun `기록이 하나도 없으면 fetch 쿼리를 돌지 않는다`() {
        val stats = statistics()
        stats.clear()

        val page = service.history(OWNER, PageRequest.of(0, 20))

        assertEquals(0, page.content.size)
        assertEquals(0, page.totalElements.toInt())
        assertEquals(1, stats.prepareStatementCount, "id 페이지 조회 한 번으로 끝난다")
    }

    @Test
    fun `같은 의류를 중복 지정해도 한 번만 담긴다`() {
        val topId = wardrobe[0].id!!
        val bottomId = wardrobe[1].id!!

        val created = service.create(OWNER, "중복 입력", listOf(topId, bottomId, topId))

        assertEquals(2, created.items.size)
        assertTrue(created.clothesIdSet() == setOf(topId, bottomId))
    }
}

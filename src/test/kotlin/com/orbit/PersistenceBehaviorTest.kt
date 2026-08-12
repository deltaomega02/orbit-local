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
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val OWNER = 42L

/**
 * 이전 Django 구현에서 빠뜨렸던 두 가지를 회귀 테스트로 고정한다.
 *  1) 트랜잭션 경계 — 아이템 저장이 실패하면 코디도 남지 않아야 한다
 *  2) N+1 — 목록 조회가 코디 수에 비례해 쿼리를 늘리면 안 된다
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

        // 같은 의류를 한 코디에 두 번 넣으면 uq_coordination_clothes 제약을 위반한다.
        try {
            transactionTemplate.execute {
                val c = Coordination(OWNER, "깨질 코디")
                c.addItem(clothes, 0)
                c.addItem(clothes, 1)
                coordinationRepository.saveAndFlush(c)
            }
            fail("unique 제약 위반이 발생해야 한다")
        } catch (expected: Exception) {
            // 제약 위반 — 여기서 트랜잭션이 롤백된다
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
        // 트랜잭션 밖에서 연관 엔티티에 접근한다 — fetch join 이 안 걸렸으면
        // 여기서 LazyInitializationException 이 나거나 추가 쿼리가 발생한다.
        val names = result.flatMap { c -> c.items.map { it.clothes.name } }

        assertEquals(3, result.size)
        assertEquals(7, names.size)
        assertEquals(
            1, stats.prepareStatementCount,
            "코디 3건 + 아이템 7건을 읽는 데 실행된 쿼리 수",
        )
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

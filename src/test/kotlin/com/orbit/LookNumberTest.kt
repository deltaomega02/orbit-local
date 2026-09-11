package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.LookCounterRepository
import com.orbit.repository.UserRepository
import com.orbit.service.CoordinationService
import com.orbit.service.LookNumberBackfill
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OWNER = 501L
private const val OTHER_OWNER = 502L

/**
 * 화면에 `LOOK 014` 로 보이는 번호의 계약.
 * 사용자별로 1부터 세고, 삭제해도 밀리거나 재사용되지 않으며(빈 번호는 허용), 동시에 만들어도 겹치지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("LOOK 번호 — 사용자별 순번의 안정성")
class LookNumberTest {

    @Autowired lateinit var service: CoordinationService
    @Autowired lateinit var backfill: LookNumberBackfill
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var lookCounterRepository: LookCounterRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private var bottomId = 0L

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        // 카운터는 코디를 지워도 남으므로(번호 재사용 금지) 직접 비운다
        lookCounterRepository.deleteAll()
        userRepository.deleteAll()
        bottomId = saveClothes("검정 슬랙스", MainCategory.BOTTOM)
    }

    private fun saveClothes(name: String, category: MainCategory, owner: Long = OWNER): Long =
        requireNotNull(clothesRepository.save(Clothes(owner, name, category)).id)

    /** 중복 조합 판정에 걸리지 않도록 코디마다 다른 상의를 쓴다. */
    private fun createLook(title: String, owner: Long = OWNER): Coordination {
        val top = saveClothes("상의 $title", MainCategory.TOP, owner)
        val bottom = if (owner == OWNER) bottomId else saveClothes("하의 $title", MainCategory.BOTTOM, owner)
        return service.create(owner, title, listOf(top, bottom))
    }

    private fun lookNo(id: Long): Int? =
        requireNotNull(coordinationRepository.findById(id).orElse(null)) { "코디 $id 가 없다" }.lookNo

    @Test
    fun `첫 코디는 1번, 그다음은 2번이다`() {
        val first = createLook("첫 코디")
        val second = createLook("두 번째 코디")

        assertEquals(1, first.lookNo)
        assertEquals(2, second.lookNo)
    }

    @Test
    fun `사용자가 둘이어도 각자 1번부터 센다`() {
        // id 는 전역 시퀀스라 두 사용자의 코디를 번갈아 저장해 id 를 섞는다
        val mine1 = createLook("내 코디 1")
        val theirs1 = createLook("남의 코디 1", OTHER_OWNER)
        val mine2 = createLook("내 코디 2")
        val theirs2 = createLook("남의 코디 2", OTHER_OWNER)

        assertEquals(listOf(1, 2), listOf(mine1.lookNo, mine2.lookNo))
        assertEquals(listOf(1, 2), listOf(theirs1.lookNo, theirs2.lookNo))
        assertTrue(
            requireNotNull(theirs1.id) > requireNotNull(mine1.id),
            "이 테스트가 뜻을 가지려면 두 사용자의 id 가 실제로 섞여 있어야 한다",
        )
    }

    @Test
    fun `코디를 지워도 남은 코디의 번호는 그대로다`() {
        val first = createLook("1번")
        val second = createLook("2번")
        val third = createLook("3번")

        service.delete(OWNER, requireNotNull(second.id))

        // 목록 순서로 다시 계산하면 3번이 2번으로 밀린다
        assertEquals(1, lookNo(requireNotNull(first.id)))
        assertEquals(3, lookNo(requireNotNull(third.id)))
    }

    @Test
    fun `지운 번호는 다시 쓰지 않는다`() {
        createLook("1번")
        val second = createLook("2번")

        service.delete(OWNER, requireNotNull(second.id))
        val next = createLook("새 코디")

        // 남은 코디의 최대값 + 1 로 계산하면 2 가 다시 나와 LOOK 002 가 다른 코디를 가리킨다
        assertEquals(3, next.lookNo)
        assertEquals(listOf(1, 3), coordinationRepository.findAll().mapNotNull { it.lookNo }.sorted())
    }

    @Test
    fun `연속으로 만들어도 번호가 겹치지 않는다`() {
        val threads = 6
        val tops = (1..threads).map { saveClothes("동시 상의 $it", MainCategory.TOP) }
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val created = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        try {
            tops.forEachIndexed { i, top ->
                pool.submit {
                    try {
                        barrier.await(10, TimeUnit.SECONDS) // 최대한 같은 순간에 출발시킨다
                        created += requireNotNull(service.create(OWNER, "동시 코디 $i", listOf(top, bottomId)).lookNo)
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "동시 생성이 끝나지 않았다")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(emptyList(), failures.toList().map { it.toString() }, "동시 생성이 실패하면 안 된다")
        assertEquals(
            (1..threads).toList(), created.sorted(),
            "동시에 만든 코디들은 1..$threads 를 하나씩 나눠 가져야 한다 (겹침도 건너뜀도 없이)",
        )
    }

    @Test
    fun `같은 사용자에게 같은 번호를 두 번 저장할 수 없다`() {
        // 락·재시도 로직이 틀려도 겹친 번호가 저장되지 않게 막는 DB 제약
        createLook("1번")

        assertThrows<DataIntegrityViolationException> {
            transactionTemplate.execute {
                coordinationRepository.saveAndFlush(Coordination(OWNER, "번호를 가로채는 코디").apply { lookNo = 1 })
            }
        }
    }

    @Test
    fun `번호가 없는 기존 코디는 생성 시각 순서대로 채워진다`() {
        // 행은 있고 look_no 만 빈 기존 DB 를 재현한다.
        // 번호는 id 가 아니라 생성 시각을 따라야 하므로 두 순서를 일부러 어긋나게 둔다.
        val a = requireNotNull(createLook("먼저 만든 것처럼 보일 코디").id)
        val b = requireNotNull(createLook("가장 먼저 만든 코디").id)
        val c = requireNotNull(createLook("가장 나중에 만든 코디").id)

        val base = Instant.parse("2026-01-01T00:00:00Z")
        clearLookNo(a, base.plusSeconds(60))
        clearLookNo(b, base)
        clearLookNo(c, base.plusSeconds(120))

        val filled = backfill.migrate()

        assertEquals(3, filled)
        assertEquals(1, lookNo(b))
        assertEquals(2, lookNo(a))
        assertEquals(3, lookNo(c))
        // 발급 이력까지 맞춰야 다음 코디가 4번으로 이어진다
        assertEquals(4, createLook("마이그레이션 이후 만든 코디").lookNo)
    }

    @Test
    fun `마이그레이션은 이미 번호가 있는 코디를 건드리지 않는다`() {
        val legacy = requireNotNull(createLook("번호가 없던 코디").id)
        val numbered = requireNotNull(createLook("이미 번호가 있는 코디").id)
        clearLookNo(legacy, Instant.parse("2026-01-01T00:00:00Z"))

        // 기동할 때마다 도는 코드라 멱등이어야 한다
        assertEquals(1, backfill.migrate())
        assertEquals(0, backfill.migrate())

        assertEquals(2, lookNo(numbered), "이미 번호가 있던 코디의 번호가 바뀌면 안 된다")
        assertEquals(3, lookNo(legacy), "남아 있는 번호와 겹치지 않는 번호를 받아야 한다")
    }

    @Test
    fun `코디 응답에 lookNo 가 내려간다`() {
        val session = TestApiClient(mockMvc, objectMapper).signUpAndLogin("look@orbit.test")
        val top = saveClothes("셔츠", MainCategory.TOP, session.userId)
        val bottom = saveClothes("슬랙스", MainCategory.BOTTOM, session.userId)

        mockMvc.perform(
            post("/api/coordinations")
                .header(HttpHeaders.AUTHORIZATION, session.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("title" to "첫 코디", "clothesIds" to listOf(top, bottom)))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.lookNo").value(1))
            // 화면이 쓰는 기존 필드는 그대로 있어야 한다
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.title").value("첫 코디"))
            .andExpect(jsonPath("$.favorite").value(false))
            .andExpect(jsonPath("$.items.length()").value(2))
    }

    /**
     * look_no 를 비우고 생성 시각을 바꿔 컬럼 추가 직후의 행을 만든다.
     * createdAt 은 val 이고 lookNo 는 서비스만 부여하므로 네이티브 SQL 로 쓴다.
     */
    private fun clearLookNo(coordinationId: Long, createdAt: Instant) {
        transactionTemplate.execute {
            entityManager.createNativeQuery(
                "update coordination set look_no = null, created_at = ?1 where id = ?2",
            )
                .setParameter(1, Timestamp.from(createdAt))
                .setParameter(2, coordinationId)
                .executeUpdate()
        }
        // 마이그레이션 대상 DB 에는 카운터 행이 없으므로 같이 지운다
        lookCounterRepository.deleteAll()
    }
}

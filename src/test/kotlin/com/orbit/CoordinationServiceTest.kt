package com.orbit

import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.service.CoordinationService
import com.orbit.service.DuplicateCoordinationException
import com.orbit.service.UnknownClothesException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OWNER = 1L
private const val OTHER_OWNER = 2L

@SpringBootTest
@DisplayName("코디 생성 — 중복 판정과 소유권")
class CoordinationServiceTest {

    @Autowired lateinit var service: CoordinationService
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository

    private var topId = 0L
    private var bottomId = 0L
    private var outerId = 0L

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        topId = save("흰 셔츠", MainCategory.TOP)
        bottomId = save("검정 슬랙스", MainCategory.BOTTOM)
        outerId = save("네이비 코트", MainCategory.OUTER)
    }

    private fun save(name: String, category: MainCategory, owner: Long = OWNER): Long =
        requireNotNull(clothesRepository.save(Clothes(owner, name, category)).id)

    @Test
    fun `코디를 만들면 아이템이 카테고리 순으로 저장된다`() {
        val created = service.create(OWNER, "출근룩", listOf(outerId, bottomId, topId))

        assertEquals(3, created.items.size)
        assertEquals(listOf(0, 1, 2), created.items.map { it.layerOrder })
        assertEquals(
            listOf(MainCategory.TOP, MainCategory.BOTTOM, MainCategory.OUTER),
            created.items.map { it.clothes.mainCategory },
        )
    }

    @Test
    fun `같은 날 같은 조합이면 DuplicateCoordinationException 을 던진다`() {
        service.create(OWNER, "출근룩", listOf(topId, bottomId))

        val e = assertThrows<DuplicateCoordinationException> {
            service.create(OWNER, "출근룩 재추천", listOf(bottomId, topId)) // 순서만 다름
        }

        assertEquals(setOf(topId, bottomId), e.clothesIds)
        assertEquals(1, coordinationRepository.count(), "중복 요청은 저장되지 않아야 한다")
    }

    @Test
    fun `한 벌이라도 다르면 중복이 아니다`() {
        service.create(OWNER, "출근룩", listOf(topId, bottomId))
        service.create(OWNER, "출근룩 아우터", listOf(topId, bottomId, outerId))

        assertEquals(2, coordinationRepository.count())
    }

    @Test
    fun `중복 판정은 사용자별로 분리된다`() {
        val otherTop = save("남의 셔츠", MainCategory.TOP, OTHER_OWNER)
        service.create(OWNER, "내 코디", listOf(topId, bottomId))

        // 다른 사용자가 같은 개수의 코디를 만들어도 영향을 주지 않는다
        val otherBottom = save("남의 바지", MainCategory.BOTTOM, OTHER_OWNER)
        service.create(OTHER_OWNER, "남의 코디", listOf(otherTop, otherBottom))

        assertEquals(2, coordinationRepository.count())
        assertEquals(1, service.todayCoordinations(OWNER).size)
    }

    @Test
    fun `남의 옷으로는 코디를 만들 수 없다`() {
        val othersTop = save("남의 셔츠", MainCategory.TOP, OTHER_OWNER)

        val e = assertThrows<UnknownClothesException> {
            service.create(OWNER, "훔친 코디", listOf(othersTop, bottomId))
        }

        assertTrue(othersTop in e.missingIds)
        assertEquals(0, coordinationRepository.count())
    }

    @Test
    fun `빈 의류 목록은 거부한다`() {
        assertThrows<IllegalArgumentException> {
            service.create(OWNER, "빈 코디", emptyList())
        }
    }
}

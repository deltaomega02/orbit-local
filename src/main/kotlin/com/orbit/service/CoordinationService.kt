package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 같은 날 이미 나온 조합이 다시 추천됐을 때. 웹 계층에서 409로 변환된다. */
class DuplicateCoordinationException(val clothesIds: Set<Long>) :
    RuntimeException("이미 오늘 추천된 조합입니다: $clothesIds")

class UnknownClothesException(val missingIds: Set<Long>) :
    RuntimeException("존재하지 않거나 소유자가 다른 의류입니다: $missingIds")

@Service
class CoordinationService(
    private val coordinationRepository: CoordinationRepository,
    private val clothesRepository: ClothesRepository,
    private val clock: Clock,
) {

    /**
     * 의류 조합으로 코디를 만든다.
     *
     * 추천 엔진은 확률적이라 같은 조합을 반복해서 내놓을 수 있다. 프롬프트로
     * 회피를 "요청"하는 것만으로는 보장이 되지 않으므로, 서버가 당일 코디를
     * 집합 비교로 검증하고 중복이면 거부한다. 호출자는 409를 받고 재시도한다.
     *
     * 코디 1건과 아이템 N건은 한 트랜잭션에서 저장된다. 중간에 실패하면 코디도
     * 남지 않는다 — 아이템이 비어 있는 코디가 생기던 이전 구현의 문제를 막는다.
     */
    @Transactional
    fun create(ownerId: Long, title: String, clothesIds: List<Long>): Coordination {
        require(clothesIds.isNotEmpty()) { "clothesIds는 비어 있을 수 없습니다" }

        val requested = clothesIds.toSet()
        val found = clothesRepository.findAllByIdInAndOwnerId(requested, ownerId)
        val foundIds = found.mapNotNull { it.id }.toSet()
        if (foundIds != requested) {
            throw UnknownClothesException(requested - foundIds)
        }

        if (isDuplicateToday(ownerId, requested)) {
            throw DuplicateCoordinationException(requested)
        }

        val coordination = Coordination(ownerId = ownerId, title = title)
        // 카테고리로 레이어 순서를 정한다. 정렬을 안정적으로 만들기 위해
        // 같은 카테고리 안에서는 id 순으로 둔다.
        found.sortedWith(compareBy({ layerOf(it) }, { it.id })).forEach {
            coordination.addItem(it, layerOf(it))
        }
        return coordinationRepository.save(coordination)
    }

    /** 오늘 만들어진 코디 중 정확히 같은 의류 집합이 있는지. */
    @Transactional(readOnly = true)
    fun isDuplicateToday(ownerId: Long, clothesIds: Set<Long>): Boolean =
        todayCoordinations(ownerId).any { it.clothesIdSet() == clothesIds }

    @Transactional(readOnly = true)
    fun todayCoordinations(ownerId: Long): List<Coordination> {
        val zone = clock.zone
        val startOfDay = LocalDate.now(clock).atStartOfDay(zone).toInstant()
        val startOfNextDay = LocalDate.now(clock).plusDays(1).atStartOfDay(zone).toInstant()
        return coordinationRepository.findCreatedBetweenWithItems(ownerId, startOfDay, startOfNextDay)
    }

    private fun layerOf(clothes: Clothes): Int = when (clothes.mainCategory) {
        MainCategory.TOP -> 0
        MainCategory.BOTTOM -> 1
        MainCategory.OUTER -> 2
    }
}

/** 시간을 주입 가능하게 둬서 "오늘" 경계를 테스트에서 고정할 수 있게 한다. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

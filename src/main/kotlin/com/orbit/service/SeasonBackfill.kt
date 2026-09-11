package com.orbit.service

import com.orbit.domain.Seasons
import com.orbit.repository.ClothesRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 예전 한국어 계절 표기를 표준값(일본어)으로 옮긴다. 기동 시 한 번 돈다.
 * 추천 프롬프트 규칙이 표준 표기를 직접 비교하므로 섞여 있으면 안 된다.
 * Flyway 가 없어 코드로 옮기며, 멱등이라 실행 여부를 기록하지 않는다.
 */
@Component
class SeasonBackfill(
    private val clothesRepository: ClothesRepository,
    private val transactionTemplate: TransactionTemplate,
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        val moved = migrate()
        if (moved > 0) {
            log.info("옛 표기의 계절 값 {}건을 표준 표기로 옮겼습니다", moved)
        }
    }

    /** 옮긴 행 수를 돌려준다. 표기별로 트랜잭션을 나눠, 실패한 표기만 다음 기동에서 재시도된다. */
    fun migrate(): Int = Seasons.LEGACY_TO_CANONICAL.entries.sumOf { (legacy, canonical) ->
        transactionTemplate.execute { clothesRepository.renameSeason(legacy, canonical) } ?: 0
    }
}

package com.orbit.service

import com.orbit.domain.LookCounter
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.LookCounterRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * LOOK 번호가 없는 기존 코디에 생성 시각 순으로 번호를 채운다. `ddl-auto: update` 는 값을 채우지 않으므로 필요하다.
 * 서버가 요청을 받기 전에 돌도록 `ApplicationRunner` 대신 [SmartInitializingSingleton] 을 쓴다.
 * 멱등이라 실행 여부를 기록하지 않는다.
 */
@Component
class LookNumberBackfill(
    private val coordinationRepository: CoordinationRepository,
    private val lookCounterRepository: LookCounterRepository,
    private val transactionTemplate: TransactionTemplate,
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        val filled = migrate()
        if (filled > 0) {
            log.info("LOOK 번호가 없던 코디 {}건에 번호를 채웠습니다", filled)
        }
    }

    /** 채운 행 수를 돌려준다. 사용자 단위로 트랜잭션을 나눠 실패한 사용자만 다음 기동에서 재시도된다. */
    fun migrate(): Int = coordinationRepository.findOwnerIdsWithoutLookNo().sumOf { migrateOwner(it) }

    private fun migrateOwner(ownerId: Long): Int = transactionTemplate.execute {
        val counter = lookCounterRepository.findForUpdate(ownerId) ?: LookCounter(ownerId)
        // 이미 번호가 있는 코디와 겹치지 않도록 발급 이력과 실제 최대값 중 큰 쪽부터 잇는다.
        var issued = maxOf(counter.lastLookNo, coordinationRepository.findMaxLookNo(ownerId) ?: 0)

        val targets = coordinationRepository.findWithoutLookNo(ownerId)
        targets.forEach { it.lookNo = ++issued } // 더티 체킹으로 반영된다

        counter.lastLookNo = issued
        lookCounterRepository.save(counter)
        targets.size
    } ?: 0
}

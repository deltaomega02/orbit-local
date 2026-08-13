package com.orbit.service

import com.orbit.domain.LookCounter
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.LookCounterRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * LOOK 번호가 없는 기존 코디에 번호를 채운다. 기동 시 한 번 돈다.
 *
 * **왜 코드에 마이그레이션이 있는가.** 이 앱에는 Flyway 같은 스키마 마이그레이션
 * 도구가 없고, 스키마는 `ddl-auto: update` 로 따라온다. 그 방식은 컬럼을 **추가**해
 * 줄 뿐 값을 채워 주지 않는다. 이미 사용자 데이터가 들어 있는 로컬 DB 가 존재하므로
 * (앱을 쓰고 있다) 값이 비어 있는 행은 실제로 생긴다. 그 행들을 그대로 두면 화면에
 * 번호가 없는 코디가 섞여 나온다.
 *
 * **왜 생성 시각 순인가.** 번호는 "몇 번째로 만든 코디인가"를 뜻한다. 소급해서 매길
 * 때 그 뜻을 지키는 순서는 생성 시각뿐이다. id 순도 대개 같은 결과지만, id 는 전역
 * 시퀀스라 뜻이 다른 값이다 — 지금 고치려는 문제가 바로 그 혼동이다.
 *
 * **왜 [SmartInitializingSingleton] 인가.** 컨텍스트가 다 뜬 뒤, 웹 서버가 요청을
 * 받기 시작하기 전에 실행된다. `ApplicationRunner` 는 서버가 이미 포트를 연 뒤에
 * 돌아서, 그 사이에 들어온 조회가 번호 없는 코디를 볼 수 있다.
 *
 * 한 번 채운 뒤에는 대상이 0건이라 사실상 조회 한 번으로 끝난다. 그래서 "이미
 * 실행했는가"를 따로 기록하지 않는다 — 여러 번 돌아도 결과가 같고(멱등), 도중에
 * 죽어도 다음 기동이 남은 것부터 이어서 채운다.
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

    /**
     * 채운 행 수를 돌려준다. 사용자 단위로 트랜잭션을 끊는다 — 한 사용자에서
     * 실패해도 다른 사용자의 번호는 이미 커밋돼 있고, 실패한 쪽만 다음 기동에서
     * 다시 시도된다. 전부를 한 트랜잭션에 묶으면 코디 한 건 때문에 전체가 되돌아간다.
     */
    fun migrate(): Int = coordinationRepository.findOwnerIdsWithoutLookNo().sumOf { migrateOwner(it) }

    private fun migrateOwner(ownerId: Long): Int = transactionTemplate.execute {
        val counter = lookCounterRepository.findForUpdate(ownerId) ?: LookCounter(ownerId)
        // 이미 번호가 있는 코디(예: 마이그레이션이 도중에 끊긴 뒤 만들어진 코디)와
        // 겹치지 않도록 발급 이력과 실제 최대값 중 큰 쪽에서 이어 붙인다.
        var issued = maxOf(counter.lastLookNo, coordinationRepository.findMaxLookNo(ownerId) ?: 0)

        val targets = coordinationRepository.findWithoutLookNo(ownerId)
        targets.forEach { it.lookNo = ++issued } // 더티 체킹으로 반영된다

        counter.lastLookNo = issued
        lookCounterRepository.save(counter)
        targets.size
    } ?: 0
}

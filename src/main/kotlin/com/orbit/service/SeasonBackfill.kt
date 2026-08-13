package com.orbit.service

import com.orbit.domain.Seasons
import com.orbit.repository.ClothesRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 예전 한국어 계절 표기를 표준값(일본어)으로 옮긴다. 기동 시 한 번 돈다.
 *
 * **왜 옮겨야 하는가.** `season` 은 화면 라벨이면서 동시에 추천 프롬프트에 실려
 * 나가는 데이터다. 프롬프트의 규칙이 값의 이름을 직접 가리키므로
 * (`季節:夏` 와 `季節:冬` 을 한 벌에 섞지 마라), DB 에 `여름` 과 `夏` 가 섞여
 * 있으면 그 규칙은 절반의 옷에만 걸린다. 실패가 조용하다는 것이 특히 나쁘다 —
 * 추천은 계속 나오고, 다만 계절이 안 맞을 뿐이다.
 *
 * **왜 코드에 마이그레이션이 있는가.** [LookNumberBackfill] 과 같은 이유다.
 * Flyway 같은 도구가 없고 스키마는 `ddl-auto: update` 로 따라오는데, 그건 컬럼을
 * 추가해 줄 뿐 값을 바꿔 주지 않는다. 이미 사용자 데이터가 든 로컬 DB 가 존재한다.
 *
 * 표기별로 UPDATE 한 번씩, 전부 합쳐 몇 건이다. 한 번 옮긴 뒤에는 대상이 0건이라
 * 사실상 빈 UPDATE 몇 개로 끝나므로 "이미 실행했는가"를 따로 기록하지 않는다 —
 * 여러 번 돌아도 결과가 같고(멱등), 도중에 죽어도 다음 기동이 이어서 옮긴다.
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

    /**
     * 옮긴 행 수를 돌려준다. 표기 하나가 곧 트랜잭션 하나다 — 한 표기에서 실패해도
     * 앞서 옮긴 것은 이미 커밋돼 있고, 실패한 표기만 다음 기동에서 다시 시도된다.
     */
    fun migrate(): Int = Seasons.LEGACY_TO_CANONICAL.entries.sumOf { (legacy, canonical) ->
        transactionTemplate.execute { clothesRepository.renameSeason(legacy, canonical) } ?: 0
    }
}

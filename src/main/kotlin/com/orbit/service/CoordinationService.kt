package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.LookCounter
import com.orbit.domain.MainCategory
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.LookCounterRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 같은 날 이미 나온 조합이 다시 추천됐을 때. 웹 계층에서 409로 변환된다. */
class DuplicateCoordinationException(val clothesIds: Set<Long>) :
    RuntimeException("이미 오늘 추천된 조합입니다: $clothesIds")

class UnknownClothesException(val missingIds: Set<Long>) :
    RuntimeException("존재하지 않거나 소유자가 다른 의류입니다: $missingIds")

/**
 * LOOK 번호 충돌 시 최대 시도 횟수. 충돌은 첫 코디에서 카운터 행을 만들 때만 생겨
 * 두 번째 시도면 끝나며, 3 은 여유를 둔 상한이다.
 */
private const val LOOK_NO_MAX_ATTEMPTS = 3

@Service
class CoordinationService(
    private val coordinationRepository: CoordinationRepository,
    private val mediaStorage: MediaStorage,
    private val creator: CoordinationCreator,
) {

    /**
     * 의류 조합으로 코디를 만든다. 당일 같은 조합이면 409 로 거부한다.
     * 유니크 제약 위반 뒤에는 같은 트랜잭션에서 재시도할 수 없으므로, 트랜잭션 없이
     * [CoordinationCreator.createOnce] 를 감싸는 재시도 루프로 둔다. 마지막 예외는 그대로 올린다.
     */
    fun create(
        ownerId: Long,
        title: String,
        clothesIds: List<Long>,
        reason: String? = null,
        situation: String? = null,
    ): Coordination {
        repeat(LOOK_NO_MAX_ATTEMPTS - 1) {
            try {
                return creator.createOnce(ownerId, title, clothesIds, reason, situation)
            } catch (retryable: DataIntegrityViolationException) {
                // 다른 요청이 같은 LOOK 번호를 먼저 가져감. 새 트랜잭션에서 다시 시도한다.
            } catch (retryable: ConcurrencyFailureException) {
                // 카운터 행 락 타임아웃. 예외 타입이 DB 마다 달라 상위 타입으로 받는다.
            }
        }
        return creator.createOnce(ownerId, title, clothesIds, reason, situation)
    }

    /** 오늘 만들어진 코디 중 정확히 같은 의류 집합이 있는지. */
    fun isDuplicateToday(ownerId: Long, clothesIds: Set<Long>): Boolean =
        creator.isDuplicateToday(ownerId, clothesIds)

    fun todayCoordinations(ownerId: Long): List<Coordination> = creator.todayCoordinations(ownerId)

    /**
     * 전체 기록, 최신순. 컬렉션 fetch join 과 limit 을 섞지 않도록 id 페이지 조회와 fetch 를 나눈다
     * ([CoordinationRepository.findIdPageByOwnerId] 참고).
     */
    @Transactional(readOnly = true)
    fun history(ownerId: Long, pageable: Pageable): Page<Coordination> =
        pageOfIds(coordinationRepository.findIdPageByOwnerId(ownerId, pageable), ownerId, pageable)

    /**
     * 이 옷이 쓰인 코디들. 남의 옷 id 에 404 를 주는 것은 [ClothesService] 쪽 확인이 맡고,
     * 여기서도 소유자 조건으로 조회한다.
     */
    @Transactional(readOnly = true)
    fun byClothes(ownerId: Long, clothesId: Long, pageable: Pageable): Page<Coordination> =
        pageOfIds(coordinationRepository.findIdPageByClothesId(ownerId, clothesId, pageable), ownerId, pageable)

    private fun pageOfIds(ids: Page<Long>, ownerId: Long, pageable: Pageable): Page<Coordination> {
        if (ids.isEmpty) return PageImpl(emptyList(), pageable, ids.totalElements)
        val loaded = coordinationRepository.findAllByIdsWithItems(ownerId, ids.content)
        return PageImpl(loaded, pageable, ids.totalElements)
    }

    @Transactional(readOnly = true)
    fun get(ownerId: Long, id: Long): Coordination =
        coordinationRepository.findByIdAndOwnerIdWithItems(id, ownerId)
            ?: throw CoordinationNotFoundException(id)

    /**
     * 코디 삭제. 아이템 행은 cascade 로 지워지고 의류는 남는다.
     * 가상 착용 이미지 파일은 커밋 이후에 지운다.
     */
    @Transactional
    fun delete(ownerId: Long, id: Long) {
        val coordination = coordinationRepository.findByIdAndOwnerIdWithItems(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        val tryOnImagePath = coordination.tryOnImagePath
        coordinationRepository.delete(coordination)

        if (tryOnImagePath != null) deleteFileAfterCommit(tryOnImagePath)
    }

    /**
     * 가상 착용 이미지만 지우고 코디는 남긴다. [OutfitAiService.generateTryOn] 을 다시 부를 수 있게 된다.
     * 이미지가 없어도 성공(멱등)이고, 없는 코디나 남의 코디는 404. 파일은 커밋 이후에 지운다.
     */
    @Transactional
    fun deleteTryOn(ownerId: Long, id: Long) {
        val coordination = coordinationRepository.findByIdAndOwnerId(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        val tryOnImagePath = coordination.tryOnImagePath ?: return
        coordination.tryOnImagePath = null // 더티 체킹으로 반영된다
        deleteFileAfterCommit(tryOnImagePath)
    }

    /** 롤백 시 DB 에는 경로가 남고 파일만 사라지는 일이 없도록 커밋 뒤에만 지운다. */
    private fun deleteFileAfterCommit(relativePath: String) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = mediaStorage.deleteQuietly(relativePath)
            },
        )
    }

    /** 즐겨찾기 토글. 클라이언트의 오래된 값에 영향받지 않도록 서버에서 뒤집고, 바뀐 값을 돌려준다. */
    @Transactional
    fun toggleFavorite(ownerId: Long, id: Long): Boolean {
        val coordination = coordinationRepository.findByIdAndOwnerId(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        coordination.favorite = !coordination.favorite // 더티 체킹으로 반영된다
        return coordination.favorite
    }

}

/**
 * 코디 생성 한 번의 트랜잭션. 자기 호출은 프록시를 타지 않아 [CoordinationService] 와 빈을 나눴다.
 * 중복 판정도 생성 트랜잭션 안에서 한다.
 */
@Service
class CoordinationCreator(
    private val coordinationRepository: CoordinationRepository,
    private val clothesRepository: ClothesRepository,
    private val lookCounterRepository: LookCounterRepository,
    private val clock: Clock,
) {

    /**
     * 코디, 아이템, LOOK 번호 발급을 한 트랜잭션으로 묶는다.
     * 실패한 시도가 번호를 소모하지 않도록 번호 발급을 따로 커밋하지 않는다.
     */
    @Transactional
    fun createOnce(
        ownerId: Long,
        title: String,
        clothesIds: List<Long>,
        reason: String?,
        situation: String? = null,
    ): Coordination {
        require(clothesIds.isNotEmpty()) { "clothesIds を空にはできません" }

        val requested = clothesIds.toSet()
        // 소프트 삭제된 옷은 없는 옷과 같이 취급한다.
        val found = clothesRepository.findAllByIdInAndOwnerIdAndDeletedAtIsNull(requested, ownerId)
        val foundIds = found.mapNotNull { it.id }.toSet()
        if (foundIds != requested) {
            throw UnknownClothesException(requested - foundIds)
        }

        if (isDuplicateToday(ownerId, requested)) {
            throw DuplicateCoordinationException(requested)
        }

        val coordination = Coordination(
            ownerId = ownerId,
            title = title,
            reason = reason,
            // 정규화는 호출부([OutfitAiService.recommend])에서만 한다.
            situation = situation,
        )
        coordination.lookNo = allocateLookNo(ownerId)
        // 레이어는 카테고리 순, 같은 카테고리는 id 순
        found.sortedWith(compareBy({ layerOf(it) }, { it.id })).forEach {
            coordination.addItem(it, layerOf(it))
        }
        return coordinationRepository.save(coordination)
    }

    /**
     * 다음 LOOK 번호 발급. 삭제된 번호를 재사용하지 않도록 최대값이 아니라 [LookCounter] 이력에서 잇는다.
     * 카운터 행은 쓰기 락으로 읽고, 행이 없을 때의 경합은 `(owner_id, look_no)` 유니크 제약과
     * [CoordinationService.create] 의 재시도가 막는다.
     */
    private fun allocateLookNo(ownerId: Long): Int {
        val counter = lookCounterRepository.findForUpdate(ownerId) ?: LookCounter(ownerId)
        // 카운터가 뒤처져 있어도 이미 쓰인 번호를 내주지 않도록 큰 쪽에서 잇는다.
        val issued = maxOf(counter.lastLookNo, coordinationRepository.findMaxLookNo(ownerId) ?: 0)
        counter.lastLookNo = issued + 1
        lookCounterRepository.save(counter)
        return counter.lastLookNo
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

/** 테스트에서 "오늘" 경계를 고정할 수 있도록 Clock 을 빈으로 둔다. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

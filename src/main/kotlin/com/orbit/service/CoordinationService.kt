package com.orbit.service

import com.orbit.domain.Clothes
import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

@Service
class CoordinationService(
    private val coordinationRepository: CoordinationRepository,
    private val clothesRepository: ClothesRepository,
    private val mediaStorage: MediaStorage,
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
    fun create(ownerId: Long, title: String, clothesIds: List<Long>, reason: String? = null): Coordination {
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

        val coordination = Coordination(ownerId = ownerId, title = title, reason = reason)
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

    /**
     * 전체 기록. 최신순.
     *
     * 목록 조회의 쿼리 수를 코디 건수와 무관하게 3회로 고정한다. 자세한 이유는
     * [CoordinationRepository.findIdPageByOwnerId] 주석에 적어 뒀다 — 요약하면
     * 컬렉션 fetch join 과 limit 을 한 쿼리에 섞으면 페이지네이션이 조용히
     * 사라지기 때문에, id 페이지 조회와 fetch 를 분리했다.
     */
    @Transactional(readOnly = true)
    fun history(ownerId: Long, pageable: Pageable): Page<Coordination> =
        pageOfIds(coordinationRepository.findIdPageByOwnerId(ownerId, pageable), ownerId, pageable)

    /**
     * 이 옷이 실제로 쓰인 코디들. "이 옷으로 뭘 입었었지"에 답한다.
     *
     * 소유권은 두 번 확인된다. 옷 자체를 [ClothesService] 가 소유자 조건으로 찾고,
     * 코디도 소유자 조건으로 조회한다. 중복처럼 보이지만 "남의 옷 id 로 물었을 때
     * 빈 목록이 아니라 404 가 나오는가"는 옷 쪽에서만 답할 수 있다.
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
     * 삭제. 가상 착용 이미지 파일까지 같이 지운다.
     *
     * 파일 삭제를 **커밋 이후**로 미룬다. 트랜잭션 안에서 지우면 뒤에 커밋이 실패했을 때
     * 행은 남고 파일만 사라져, 화면에 깨진 이미지가 뜬다. 반대로 커밋은 됐는데 파일
     * 삭제가 실패하면 아무도 참조하지 않는 파일이 하나 남을 뿐이다 — 두 사고 중
     * 어느 쪽을 감수할지의 문제이고, 사용자에게 보이는 쪽이 더 나쁘다.
     *
     * 코디에 물린 아이템 행은 cascade + orphanRemoval 로 함께 사라진다. 아이템이
     * 가리키던 **의류는 지우지 않는다.** 코디를 지웠다고 옷장에서 옷이 없어지면
     * 그게 더 놀라운 동작이다.
     */
    @Transactional
    fun delete(ownerId: Long, id: Long) {
        val coordination = coordinationRepository.findByIdAndOwnerIdWithItems(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        val tryOnImagePath = coordination.tryOnImagePath
        coordinationRepository.delete(coordination)

        if (tryOnImagePath != null) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = mediaStorage.deleteQuietly(tryOnImagePath)
                },
            )
        }
    }

    /**
     * 즐겨찾기 토글. 현재 값을 뒤집고 바뀐 값을 돌려준다.
     *
     * `PUT {favorite: true}` 로 값을 지정받지 않고 토글로 둔 이유: 화면의 동작이
     * "별을 누른다" 하나뿐이라, 클라이언트가 현재 값을 들고 있다가 반대 값을
     * 계산해 보내면 화면이 오래됐을 때 눌러도 아무 일이 없는 것처럼 보인다.
     * 서버가 뒤집으면 두 번 누르면 반드시 제자리로 온다.
     */
    @Transactional
    fun toggleFavorite(ownerId: Long, id: Long): Boolean {
        val coordination = coordinationRepository.findByIdAndOwnerId(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        coordination.favorite = !coordination.favorite // 더티 체킹으로 반영된다
        return coordination.favorite
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

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
 * LOOK 번호가 겹쳐 저장이 실패했을 때의 최대 시도 횟수.
 *
 * 3 인 이유는 근거가 있어서가 아니라 없어서다 — 카운터 행에 쓰기 락을 걸고 번호를
 * 받으므로 정상 경로에서 이 재시도는 돌지 않는다. 첫 코디를 만들며 카운터 행 자체를
 * 만드는 순간이 유일하게 락으로 막지 못하는 구간이고, 그때 진 쪽은 다음 시도에서
 * 이미 만들어진 행을 잠그고 읽는다. 즉 두 번째 시도면 끝난다. 3 은 거기에 한 번을
 * 더 얹은 값이고, 무한 루프가 되지 않게 상한을 두는 것이 목적이다.
 */
private const val LOOK_NO_MAX_ATTEMPTS = 3

@Service
class CoordinationService(
    private val coordinationRepository: CoordinationRepository,
    private val mediaStorage: MediaStorage,
    private val creator: CoordinationCreator,
) {

    /**
     * 의류 조합으로 코디를 만든다.
     *
     * 추천 엔진은 확률적이라 같은 조합을 반복해서 내놓을 수 있다. 프롬프트로
     * 회피를 "요청"하는 것만으로는 보장이 되지 않으므로, 서버가 당일 코디를
     * 집합 비교로 검증하고 중복이면 거부한다. 호출자는 409를 받고 재시도한다.
     *
     * **여기에 트랜잭션이 없는 것이 이 메서드의 존재 이유다.** 실제 저장은
     * [CoordinationCreator.createOnce] 한 번이 곧 트랜잭션 하나이고, 이 메서드는
     * 그 시도를 감싸는 재시도 루프다. LOOK 번호가 겹치면 `(owner_id, look_no)`
     * 유니크 제약이 저장을 실패시키는데, 그 시점의 트랜잭션은 이미 롤백 대상이라
     * **같은 트랜잭션 안에서는 재시도할 수 없다.** 새 트랜잭션이 필요하고, 그러려면
     * 재시도가 트랜잭션 경계 바깥에 있어야 한다. 그래서 클래스가 둘로 나뉜다.
     *
     * 마지막 시도의 예외는 삼키지 않고 그대로 올린다. 번호를 못 받았는데 코디가
     * 저장된 것처럼 보이는 것보다, 실패가 실패로 보이는 편이 낫다.
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
                // 유니크 제약 위반 — 다른 요청이 같은 번호를 먼저 가져갔다.
                // 다음 시도는 새 트랜잭션에서 카운터를 다시 읽으므로 그 다음 번호를 받는다.
                // (이 코디에 같은 옷이 두 번 들어간 경우처럼 재시도해도 소용없는 위반도
                //  같이 걸리지만, 몇 번 더 시도한 뒤 똑같이 실패한다 — 결과는 같고
                //  대신 예외 종류로 분기하다 틀리는 코드가 없다.)
            } catch (retryable: ConcurrencyFailureException) {
                // 카운터 행 락을 기다리다 타임아웃(락 획득 실패·동시 갱신 감지).
                // 앞선 요청이 곧 끝날 일이므로 기다렸다 다시 시도한다. 구체 예외 타입은
                // DB 와 드라이버마다 달라서(H2 는 동시 갱신, MySQL 은 락 타임아웃) 공통
                // 상위 타입으로 받는다.
            }
        }
        return creator.createOnce(ownerId, title, clothesIds, reason, situation)
    }

    /** 오늘 만들어진 코디 중 정확히 같은 의류 집합이 있는지. */
    fun isDuplicateToday(ownerId: Long, clothesIds: Set<Long>): Boolean =
        creator.isDuplicateToday(ownerId, clothesIds)

    fun todayCoordinations(ownerId: Long): List<Coordination> = creator.todayCoordinations(ownerId)

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

        if (tryOnImagePath != null) deleteFileAfterCommit(tryOnImagePath)
    }

    /**
     * 가상 착용 이미지만 지운다. **코디는 남는다.**
     *
     * 이 메서드가 없던 동안 사용자에게 남은 선택지는 "룩을 통째로 지우기" 하나뿐이었다.
     * 그런데 합성 결과가 엉뚱한 사람으로 나오는 일은 실제로 있고, 그때 사용자가 버리고
     * 싶은 것은 **이미지 한 장**이지 조합·추천 이유·LOOK 번호가 아니다. 지우고 다시
     * 만들 수 있어야 [OutfitAiService.generateTryOn] 의 멱등성도 막다른 길이 되지 않는다
     * (이미 있으면 다시 만들지 않으므로, 지우는 길이 없으면 영영 그 이미지에 묶인다).
     *
     * **없어도 204 다(멱등).** "가상 착용 이미지가 없다"는 목표 상태는 이미 달성돼
     * 있고, 여기서 404 를 주면 클라이언트가 지우기 전에 조회를 한 번 더 해야 한다.
     * 반면 **남의 코디와 없는 코디는 404** 다 — 그건 상태가 아니라 권한의 문제다.
     *
     * 파일 삭제는 [delete] 와 같은 이유로 커밋 이후로 미룬다. 순서를 뒤집으면
     * 롤백됐을 때 행에는 경로가 남았는데 파일은 사라져 깨진 이미지가 화면에 뜬다.
     * 반대 순서의 최악은 아무도 참조하지 않는 파일 하나가 남는 것이다.
     */
    @Transactional
    fun deleteTryOn(ownerId: Long, id: Long) {
        val coordination = coordinationRepository.findByIdAndOwnerId(id, ownerId)
            ?: throw CoordinationNotFoundException(id)
        val tryOnImagePath = coordination.tryOnImagePath ?: return
        coordination.tryOnImagePath = null // 더티 체킹으로 반영된다
        deleteFileAfterCommit(tryOnImagePath)
    }

    /**
     * 커밋이 실제로 끝난 뒤에만 파일을 지운다. 트랜잭션이 롤백되면 아무 일도 일어나지
     * 않는다 — DB 는 그대로인데 파일만 없어지는 상태를 만들지 않는 것이 요점이다.
     */
    private fun deleteFileAfterCommit(relativePath: String) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = mediaStorage.deleteQuietly(relativePath)
            },
        )
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

}

/**
 * 코디 생성 **한 번의 트랜잭션**.
 *
 * [CoordinationService] 에서 떼어 낸 이유는 하나뿐이다 — 재시도는 트랜잭션 밖에
 * 있어야 하고, 스프링에서 같은 빈의 메서드를 자기가 호출하면 프록시를 타지 않아
 * `@Transactional` 이 걸리지 않기 때문이다. 클래스가 나뉘어야 "시도 1회 = 트랜잭션
 * 1회"가 코드 모양 그대로 성립한다.
 *
 * "오늘" 경계(=중복 판정 기준)도 여기로 같이 왔다. 중복 판정은 생성 규칙의 일부라
 * 생성 트랜잭션 안에서 읽어야 의미가 있다 — 트랜잭션 밖에서 미리 검사하면 검사와
 * 저장 사이가 벌어진다.
 */
@Service
class CoordinationCreator(
    private val coordinationRepository: CoordinationRepository,
    private val clothesRepository: ClothesRepository,
    private val lookCounterRepository: LookCounterRepository,
    private val clock: Clock,
) {

    /**
     * 코디 1건과 아이템 N건, 그리고 LOOK 번호 발급까지가 한 트랜잭션이다.
     * 중간에 실패하면 코디도 번호도 남지 않는다 — 아이템이 비어 있는 코디가 생기던
     * 이전 구현의 문제를 막고, 동시에 "저장되지도 않은 코디가 번호만 먹는" 일도 막는다.
     * (번호 발급을 별도 트랜잭션으로 먼저 커밋하면 후자가 실제로 생긴다. 중복 조합으로
     *  409 를 받고 재시도하는 흐름에서 시도할 때마다 번호가 하나씩 날아가, 삭제한 적도
     *  없는 사용자의 목록이 LOOK 001 다음 LOOK 005 가 된다.)
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
        // 옷장에서 치운 옷(소프트 삭제)으로는 새 코디를 만들 수 없다. 과거 기록에는
        // 남지만 "지금 입을 수 있는 옷"은 아니므로, 없는 옷과 똑같이 취급한다.
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
            // 값을 다듬는 곳은 호출부([OutfitAiService.recommend]) 하나다. 여기서 또
            // 자르면 규칙이 두 곳이 되고, 두 곳은 언젠가 어긋난다.
            situation = situation,
        )
        coordination.lookNo = allocateLookNo(ownerId)
        // 카테고리로 레이어 순서를 정한다. 정렬을 안정적으로 만들기 위해
        // 같은 카테고리 안에서는 id 순으로 둔다.
        found.sortedWith(compareBy({ layerOf(it) }, { it.id })).forEach {
            coordination.addItem(it, layerOf(it))
        }
        return coordinationRepository.save(coordination)
    }

    /**
     * 이 사용자의 다음 LOOK 번호를 발급한다. **번호가 정해지는 곳은 여기 하나뿐이다.**
     *
     * 남아 있는 코디의 최대값이 아니라 [LookCounter] 의 발급 이력에서 이어 붙인다.
     * 최대값 방식은 마지막 코디를 지운 직후 그 번호를 재사용해 버리기 때문이다 —
     * 번호가 가리키는 대상이 바뀌는 것은 이 앱에서 번호가 비는 것보다 나쁘다.
     * ([com.orbit.domain.Coordination.lookNo] 주석)
     *
     * 카운터 행은 쓰기 락으로 읽어 발급 구간을 사용자 단위로 직렬화한다. 그래도
     * 첫 코디에서 카운터 행 자체를 만드는 순간은 락으로 막을 수 없어(잠글 행이 아직
     * 없다) 두 요청이 같은 번호를 들고 갈 수 있는데, 그 경우는 `(owner_id, look_no)`
     * 유니크 제약이 저장을 실패시키고 [CoordinationService.create] 가 새 트랜잭션에서
     * 다시 시도한다. 세 겹 중 어느 하나도 혼자서는 충분하지 않다.
     */
    private fun allocateLookNo(ownerId: Long): Int {
        val counter = lookCounterRepository.findForUpdate(ownerId) ?: LookCounter(ownerId)
        // 카운터가 실제보다 뒤처져 있어도(직접 INSERT, 중단된 마이그레이션) 이미 쓰인
        // 번호를 다시 내주지 않도록 큰 쪽에서 이어 간다. 어긋남이 다음 발급으로 복구된다.
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

/** 시간을 주입 가능하게 둬서 "오늘" 경계를 테스트에서 고정할 수 있게 한다. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

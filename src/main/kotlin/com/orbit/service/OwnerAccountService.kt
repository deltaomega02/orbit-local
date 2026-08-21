package com.orbit.service

import com.orbit.domain.User
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.Base64

/**
 * 이 앱을 쓰는 **한 사람**의 정보.
 *
 * 값을 코드가 아니라 설정에 두는 이유는 이름이 바뀌는 값이기 때문이다. 화면에
 * 보이는 이름을 고치는 데 재빌드가 필요하면, 그건 사실상 못 고치는 것과 같다.
 *
 * `displayName` 만 화면에 나간다. `email` 은 **내부 식별자**다 — 계정 테이블의
 * 유니크 키이고 토큰의 subject 를 만드는 재료일 뿐이라, 화면에 띄울 값이 아니다.
 * 존칭(`さん` 등)은 여기에 넣지 않는다. 붙일지 말지는 화면 문구의 문제이고,
 * 이름에 박아 두면 화면이 그 판단을 되돌릴 수 없다.
 *
 * 정식 이름(`比嘉 愛里子`)을 위한 `fullName` 은 두지 않았다. 지금 이 값을 읽는
 * 화면이 하나도 없고, 아무도 읽지 않는 설정은 "언젠가 쓰겠지" 하는 사이에 실제
 * 값과 어긋나기 시작한다. 필요해지는 시점에 그 화면과 함께 넣는 편이 낫다.
 */
@ConfigurationProperties(prefix = "orbit.owner")
data class OwnerProperties(
    val displayName: String = "ユーザー",
    val email: String = "owner@orbit.local",
)

/**
 * 기본 사용자(= 이 앱의 주인) 한 명을 정하고, 없으면 만든다.
 *
 * ## 왜 필요한가
 *
 * 로그인 화면을 없앴기 때문이다([com.orbit.web.AuthController.session]). 자격증명
 * 없이 토큰을 내주려면 "누구의 토큰인가"에 서버가 스스로 답할 수 있어야 한다.
 *
 * ## 이어받기 규칙 — 옷장이 있는 사람이 주인이다
 *
 * 순서대로 본다.
 *
 *  1. **이미 주인으로 표시된 계정**([User.ownerFlag])이 있으면 그 계정. 판단은
 *     한 번만 하고 그 뒤로는 기록을 따른다.
 *  2. 없으면 **데이터가 가장 많은 계정**(옷 + 코디 수). 동점이면 설정 이메일과
 *     같은 계정, 그래도 동점이면 가장 먼저 만들어진 계정.
 *  3. 계정이 하나도 없으면 설정값으로 새로 만든다.
 *
 * **왜 설정 이메일 일치를 1순위로 두지 않는가.** 그게 가장 자연스러운 규칙처럼
 * 보이지만, 이 앱에서는 정확히 그 규칙이 사고를 낸다. 지금 DB 에 들어 있는 계정은
 * `existing@orbit.test`(옷 7벌·룩 7개)인데 설정 이메일은 `owner@orbit.local` 이다.
 * 이메일 일치만 보면 하나도 걸리지 않아 **빈 계정을 새로 만들고**, 사용자는 앱을
 * 켜자마자 텅 빈 옷장을 보게 된다. 데이터는 DB 에 그대로 있는데 화면에서만 사라지는
 * 것이라 사용자 입장에서는 "옷장을 잃었다"와 구별되지 않는다.
 *
 * 설정 이메일은 **새 계정을 만들 때 쓰는 값**이지 기존 계정을 찾는 열쇠가 아니다.
 * 사람이 바꾸는 값(설정)보다 사람이 쌓은 것(옷장)이 신뢰할 만한 신호다.
 *
 * **왜 소프트 삭제된 옷도 세는가.** 여기서 세는 것은 "지금 옷장에 몇 벌 있는가"가
 * 아니라 "이 계정을 실제로 썼는가"다. 옷을 전부 치운 계정도 쓴 흔적이 있는 계정이고,
 * 갓 만들어진 감사용 빈 계정과는 다르다.
 *
 * **왜 결정을 DB 에 기록하는가.** 규칙을 매 기동마다 다시 계산하면 데이터가 움직일
 * 때 답이 바뀔 수 있다. 주인이 바뀐다는 것은 사용자에게 곧 "옷장이 통째로 바뀐다"로
 * 보인다. 한 번 정한 뒤에는 계산이 아니라 기록을 따른다.
 */
@Service
class OwnerAccountService(
    private val userRepository: UserRepository,
    private val clothesRepository: ClothesRepository,
    private val coordinationRepository: CoordinationRepository,
    private val passwordEncoder: PasswordEncoder,
    private val properties: OwnerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주인 계정을 돌려준다. 없으면 만든다. **여러 번 불러도 결과가 같다(멱등).**
     *
     * 기동 시 한 번만 부르지 않고 세션 발급 때마다 부르는 이유는, 그 사이에 계정이
     * 사라질 수 있기 때문이다(테스트가 DB 를 비우거나, 사용자가 데이터 폴더를 지우는
     * 경우). 그때 500 대신 계정을 다시 만들어 주는 편이 이 앱에서는 맞다.
     */
    @Transactional
    fun resolveOrCreate(): User {
        val existing = markedOwner() ?: takeOver()
        if (existing != null) return syncDisplayName(existing)

        val created = userRepository.saveAndFlush(
            User(
                email = properties.email.trim().lowercase(),
                // 이 계정으로는 로그인하지 않는다. 그래도 비밀번호 컬럼은 채워야 하므로
                // 아무도(사용자 자신도) 모르는 임의 값을 넣는다. 빈 문자열이나 고정
                // 문자열을 넣으면 `/api/auth/login` 이 그 값을 아는 사람에게 열린다.
                passwordHash = passwordEncoder.encode(randomSecret()),
                displayName = displayName(),
            ).also { it.ownerFlag = true },
        )
        log.info("기본 사용자를 새로 만들었습니다: id={}", created.id)
        return created
    }

    /** 이미 주인으로 표시된 계정. 표시가 여러 개면 가장 오래된 쪽을 따른다(결정적으로). */
    private fun markedOwner(): User? =
        userRepository.findAll().filter { it.ownerFlag == true }.minByOrNull { it.createdAt }

    /**
     * 표시가 없을 때 기존 계정 중에서 주인을 고른다. 위 클래스 주석의 2번 규칙이다.
     * 고른 뒤에는 표시를 남겨서 다음 기동부터 이 계산이 다시 돌지 않게 한다.
     */
    private fun takeOver(): User? {
        val configured = properties.email.trim().lowercase()
        // 흔적은 계정당 한 번만 센다. 비교자 안에서 세면 정렬 도중 같은 쿼리가 반복된다.
        val footprints = userRepository.findAll().associateWith { footprint(requireNotNull(it.id)) }
        val candidate = footprints.keys
            .maxWithOrNull(
                compareBy<User> { footprints.getValue(it) }
                    .thenBy { if (it.email == configured) 1 else 0 }
                    // createdAt 은 작을수록(오래될수록) 우선이므로 부호를 뒤집는다.
                    .thenByDescending { it.createdAt },
            )
            ?: return null

        candidate.ownerFlag = true
        log.info(
            "기존 계정을 기본 사용자로 이어받습니다: id={}, 데이터 {}건",
            candidate.id,
            footprints.getValue(candidate),
        )
        return candidate
    }

    /** 이 계정에 남은 흔적의 크기. 옷(치운 것 포함) + 코디 수. */
    private fun footprint(userId: Long): Long =
        clothesRepository.countByOwnerId(userId) + coordinationRepository.countByOwnerId(userId)

    /**
     * 화면에 보이는 이름은 **설정이 진실**이다. 이어받은 계정의 이름이 예전 값이면
     * 설정값으로 맞춘다 — "설정 한 줄로 이름을 바꾼다"가 성립하려면 이 동기화가
     * 있어야 한다. 이메일은 건드리지 않는다. 식별자를 바꾸는 것은 계정을 옮기는
     * 일이고, 화면에 나가지도 않는 값을 위해 그런 위험을 질 이유가 없다.
     */
    private fun syncDisplayName(user: User): User {
        val wanted = displayName()
        if (user.displayName != wanted) user.displayName = wanted
        return user
    }

    private fun displayName(): String =
        properties.displayName.trim().ifBlank { properties.email.substringBefore('@') }.take(40)

    private fun randomSecret(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}

/**
 * 첫 기동에 주인 계정을 만들어 둔다.
 *
 * 없어도 첫 `POST /api/auth/session` 이 같은 일을 하지만, 그러면 "계정이 언제
 * 생기는가"가 첫 요청에 달리게 된다. 기동 시점에 만들어 두면 로그에 그 사실이
 * 남고, 이어받기가 잘못 골랐을 때 사용자가 화면을 열기 전에 로그로 알 수 있다.
 *
 * [SmartInitializingSingleton] 을 쓰는 이유는 [LookNumberBackfill] 과 같다 —
 * 컨텍스트가 다 뜬 뒤, 서버가 요청을 받기 시작하기 전에 돌아야 한다.
 */
@Component
class OwnerAccountInitializer(
    private val ownerAccountService: OwnerAccountService,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        runCatching { ownerAccountService.resolveOrCreate() }
            .onFailure {
                // 여기서 실패해도 앱은 떠야 한다. 세션 발급 때 다시 시도되고,
                // 그때의 실패는 사용자에게 오류로 보이는 편이 낫다.
                LoggerFactory.getLogger(javaClass).warn("기본 사용자 준비에 실패했습니다", it)
            }
    }
}

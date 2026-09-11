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
 * 앱 주인 한 명의 정보. `displayName` 만 화면에 표시하고(존칭 없이), `email` 은 내부 식별자로만 쓴다.
 */
@ConfigurationProperties(prefix = "orbit.owner")
data class OwnerProperties(
    val displayName: String = "ユーザー",
    val email: String = "owner@orbit.local",
)

/**
 * 로그인 없이 토큰을 발급할 주인 계정을 정한다. 우선순위는
 * 1) [User.ownerFlag] 표시된 계정 2) 데이터(옷+코디, 소프트 삭제 포함)가 가장 많은 계정 3) 설정값으로 새로 생성.
 * 설정 이메일은 동점일 때만 본다(기존 데이터가 다른 이메일 계정에 있을 수 있음). 정한 결과는 DB 에 기록한다.
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

    /** 주인 계정을 돌려주고, 없으면 만든다(멱등). 계정이 사라질 수 있어 세션 발급 때마다 부른다. */
    @Transactional
    fun resolveOrCreate(): User {
        val existing = markedOwner() ?: takeOver()
        if (existing != null) return syncDisplayName(existing)

        val created = userRepository.saveAndFlush(
            User(
                email = properties.email.trim().lowercase(),
                // 고정값이면 `/api/auth/login` 으로 로그인할 수 있으므로 임의 값을 넣는다.
                passwordHash = passwordEncoder.encode(randomSecret()),
                displayName = displayName(),
            ).also { it.ownerFlag = true },
        )
        log.info("기본 사용자를 새로 만들었습니다: id={}", created.id)
        return created
    }

    /** 이미 주인으로 표시된 계정. 여러 개면 가장 오래된 계정. */
    private fun markedOwner(): User? =
        userRepository.findAll().filter { it.ownerFlag == true }.minByOrNull { it.createdAt }

    /** 기존 계정 중 주인을 골라 표시를 남긴다(클래스 주석의 2번). */
    private fun takeOver(): User? {
        val configured = properties.email.trim().lowercase()
        // 비교자 안에서 세면 같은 쿼리가 반복되므로 미리 센다.
        val footprints = userRepository.findAll().associateWith { footprint(requireNotNull(it.id)) }
        val candidate = footprints.keys
            .maxWithOrNull(
                compareBy<User> { footprints.getValue(it) }
                    .thenBy { if (it.email == configured) 1 else 0 }
                    // 오래된 계정 우선
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

    /** 옷(소프트 삭제 포함) + 코디 수. */
    private fun footprint(userId: Long): Long =
        clothesRepository.countByOwnerId(userId) + coordinationRepository.countByOwnerId(userId)

    /**
     * 화면 이름을 설정값으로 맞춘다. 사용자가 앱에서 직접 바꾼 경우([User.displayNameCustomized])는 건드리지 않는다.
     * 이메일은 식별자라 동기화하지 않는다.
     */
    private fun syncDisplayName(user: User): User {
        if (user.displayNameCustomized == true) return user
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
 * 기동 시 주인 계정을 미리 정해 로그에 남긴다. 첫 세션 요청에서도 같은 일을 한다.
 * 서버가 요청을 받기 전에 돌도록 [SmartInitializingSingleton] 을 쓴다.
 */
@Component
class OwnerAccountInitializer(
    private val ownerAccountService: OwnerAccountService,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        runCatching { ownerAccountService.resolveOrCreate() }
            .onFailure {
                // 실패해도 기동은 계속한다. 세션 발급 때 다시 시도된다.
                LoggerFactory.getLogger(javaClass).warn("기본 사용자 준비에 실패했습니다", it)
            }
    }
}

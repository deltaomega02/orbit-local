package com.orbit.service

import com.orbit.domain.User
import com.orbit.media.MediaStorage
import com.orbit.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class UserNotFoundException(val id: Long) : RuntimeException("사용자를 찾을 수 없습니다: $id")

/** 화면 이름은 비울 수 없다. [com.orbit.web.ApiExceptionHandler] 의 400 처리를 타도록 `IllegalArgumentException` 을 상속한다. */
class BlankDisplayNameException : IllegalArgumentException("表示名を入力してください。")

@Service
class UserService(
    private val userRepository: UserRepository,
    private val mediaStorage: MediaStorage,
) {

    @Transactional(readOnly = true)
    fun me(id: Long): User = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }

    /**
     * 전신 사진 교체. DB 에 경로만 있고 파일이 없는 상태를 막기 위해 파일을 먼저 쓴다.
     * 이전 사진은 지운다.
     */
    @Transactional
    fun updateBodyPhoto(id: Long, bytes: ByteArray, declaredContentType: String?): String {
        val user = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }
        val stored = mediaStorage.store("body", bytes, declaredContentType)
        val previous = user.bodyPhotoPath
        user.bodyPhotoPath = stored.relativePath
        userRepository.saveAndFlush(user)
        previous?.let { mediaStorage.deleteQuietly(it) }
        return stored.relativePath
    }

    /**
     * 화면 이름 교체. 빈 값은 거절하고 DB 컬럼 길이(40자)에 맞춰 자른다.
     * [OwnerAccountService] 가 기동 시 설정값으로 되돌리지 않도록 displayNameCustomized 를 세운다.
     */
    @Transactional
    fun updateDisplayName(id: Long, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw BlankDisplayNameException()
        val user = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }
        user.displayName = trimmed.take(40)
        user.displayNameCustomized = true
        return user.displayName // 더티 체킹으로 반영된다
    }

    /**
     * 스타일 선호도 교체. 빈 값이면 null 로 지운다.
     * 매 추천 프롬프트에 들어가므로 DB 컬럼 길이와 같은 200자에서 자른다.
     */
    @Transactional
    fun updateStylePreference(id: Long, preference: String?): String? {
        val user = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }
        user.stylePreference = preference?.trim()?.ifEmpty { null }?.take(200)
        return user.stylePreference // 더티 체킹으로 반영된다
    }
}

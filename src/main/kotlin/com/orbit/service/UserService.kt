package com.orbit.service

import com.orbit.domain.User
import com.orbit.media.MediaStorage
import com.orbit.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class UserNotFoundException(val id: Long) : RuntimeException("사용자를 찾을 수 없습니다: $id")

@Service
class UserService(
    private val userRepository: UserRepository,
    private val mediaStorage: MediaStorage,
) {

    @Transactional(readOnly = true)
    fun me(id: Long): User = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }

    /**
     * 전신 사진 교체.
     *
     * 파일을 먼저 쓰고 DB 를 갱신한다. 순서를 뒤집으면 DB 에는 경로가 있는데 파일이
     * 없는 상태가 생기고, 그건 화면에서 깨진 이미지로 드러난다. 반대 방향의 사고
     * (파일은 있는데 DB 갱신 실패)는 쓰이지 않는 파일이 하나 남을 뿐이라 덜 나쁘다.
     *
     * 사람은 전신 사진을 여러 장 들고 다니지 않으므로 옛 사진은 지운다.
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
}

package com.orbit.service

import com.orbit.domain.User
import com.orbit.media.MediaStorage
import com.orbit.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class UserNotFoundException(val id: Long) : RuntimeException("사용자를 찾을 수 없습니다: $id")

/**
 * 화면 이름을 빈 값으로 바꾸려 했다. 이름은 비울 수 없다.
 *
 * `IllegalArgumentException` 을 상속하는 이유는 [com.orbit.web.ApiExceptionHandler] 에
 * 이미 그 타입을 400 으로 내리는 자리가 있기 때문이다. 같은 뜻의 핸들러를 하나 더
 * 만들면 나중에 둘 중 하나만 고쳐지는 자리가 된다.
 */
class BlankDisplayNameException : IllegalArgumentException("表示名を入力してください。")

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

    /**
     * 화면에 보이는 이름 교체.
     *
     * 저장과 동시에 [com.orbit.domain.User.displayNameCustomized] 를 세운다. 이 표시가
     * 없으면 다음 기동에 [OwnerAccountService] 가 설정값으로 되돌려 버린다 — 바꾸는 화면을
     * 만들어 놓고 되돌리면 바꾼 적이 없는 것과 같으므로, "바꿨다"는 사실 자체를 남긴다.
     *
     * 빈 값은 거절한다. 스타일 선호도와 달리 이름은 **없어도 되는 값이 아니다** —
     * 화면 곳곳에서 사람을 부르는 데 쓰이고, 비면 부를 말이 사라진다. 지우는 동작 대신
     * "되돌리기"가 필요하면 설정값으로 돌아가는 별도 동작이어야 하지, 빈 문자열 저장이 아니다.
     *
     * 40자에서 자른다. DB 컬럼 길이와 같은 값이라 여기서 자르지 않으면 저장 시점에 실패한다.
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
     * 스타일 선호도 교체.
     *
     * 빈 문자열/공백만 보내면 null 로 지운다. "지우기" 엔드포인트를 따로 두지 않은
     * 이유는 이 값이 컬렉션이 아니라 단일 값이라 "빈 값으로 치환"이 곧 삭제이기
     * 때문이다. 화면에서도 입력칸을 비우고 저장하는 동작 하나뿐이다.
     *
     * 200자에서 자른다. 이 문장은 추천을 부를 때마다 프롬프트에 실려 나가므로
     * 길이가 그대로 토큰 비용이고, 길수록 나머지 규칙이 묻힌다. DB 컬럼 길이와
     * 같은 값이라 여기서 자르지 않으면 저장 시점에 잘리거나 실패한다.
     */
    @Transactional
    fun updateStylePreference(id: Long, preference: String?): String? {
        val user = userRepository.findById(id).orElseThrow { UserNotFoundException(id) }
        user.stylePreference = preference?.trim()?.ifEmpty { null }?.take(200)
        return user.stylePreference // 더티 체킹으로 반영된다
    }
}

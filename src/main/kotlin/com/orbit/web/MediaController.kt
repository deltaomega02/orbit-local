package com.orbit.web

import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import com.orbit.security.AuthenticatedUser
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** 저장 경로를 클라이언트가 쓰는 URL 로 바꾼다. DB 에는 경로만 두고 URL 은 여기서만 만든다. */
fun mediaUrl(relativePath: String?): String? = relativePath?.let { "/media/$it" }

/**
 * "이 경로의 파일을 이 사용자가 볼 수 있는가"의 단일 판단 지점.
 *
 * 경로에 ownerId 를 끼워 넣고 문자열을 비교하는 방법도 있지만, 그러면 URL 만 봐도
 * 내부 사용자 id 가 드러나고 무엇보다 **경로 자체가 권한이 되어 버린다**. 소유권의
 * 근거는 DB 한 곳이어야 한다. 로컬 앱이라 조회 한 번 더 도는 비용은 문제가 되지 않는다.
 */
@Component
class MediaAccessPolicy(
    private val clothesRepository: ClothesRepository,
    private val coordinationRepository: CoordinationRepository,
    private val userRepository: UserRepository,
) {
    fun isAccessible(ownerId: Long, relativePath: String): Boolean =
        clothesRepository.existsByOwnerIdAndImagePath(ownerId, relativePath) ||
            coordinationRepository.existsByOwnerIdAndTryOnImagePath(ownerId, relativePath) ||
            userRepository.existsByIdAndBodyPhotoPath(ownerId, relativePath)
}

/**
 * 미디어 서빙.
 *
 * 원본 Django 는 `MEDIA_URL` 을 그대로 열어 뒀다. 경로만 알면 누구의 옷 사진이든,
 * 심지어 전신 사진까지 인증 없이 볼 수 있었다. URL 이 UUID 라 "추측하기 어렵다"는
 * 건 접근 제어가 아니라 그냥 운이다 — 링크 한 번 새면 끝이다.
 *
 * 여기서는 정적 리소스 핸들러에 맡기지 않고 컨트롤러를 거치게 해서, 인증된
 * 사용자의 소유 파일만 나가게 한다. 없는 파일과 남의 파일은 똑같이 404 다.
 * (403 을 주면 "그 경로에 파일은 있다"를 알려주는 꼴이다)
 */
@RestController
class MediaController(
    private val storage: MediaStorage,
    private val accessPolicy: MediaAccessPolicy,
) {

    @GetMapping("/media/{*path}")
    fun serve(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable path: String,
    ): ResponseEntity<ByteArray> {
        val relativePath = path.removePrefix("/")
        if (!accessPolicy.isAccessible(user.id, relativePath)) return ResponseEntity.notFound().build()

        val bytes = storage.read(relativePath) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(storage.contentTypeOf(relativePath)))
            // 파일명이 UUID 라 내용이 바뀌지 않는다. 다만 개인 이미지이므로 공유 캐시에는 남기지 않는다.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
            .body(bytes)
    }
}

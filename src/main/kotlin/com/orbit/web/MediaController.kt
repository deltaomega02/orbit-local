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

/** DB 에는 상대 경로만 저장하고 URL 은 여기서만 만든다. */
fun mediaUrl(relativePath: String?): String? = relativePath?.let { "/media/$it" }

/** 미디어 접근 권한은 경로 문자열이 아니라 DB 의 소유 관계로 판단한다. */
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
 * 인증된 사용자의 소유 파일만 내보낸다.
 * 파일 존재 여부가 드러나지 않도록 없는 파일과 남의 파일 모두 404.
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
            // UUID 파일명이라 내용이 바뀌지 않음. 개인 이미지라 private 캐시만 허용
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
            .body(bytes)
    }
}

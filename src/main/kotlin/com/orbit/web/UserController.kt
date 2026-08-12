package com.orbit.web

import com.orbit.security.AuthenticatedUser
import com.orbit.service.UserService
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class MeResponse(
    val id: Long,
    val email: String,
    val displayName: String,
    val bodyPhotoUrl: String?,
)

data class BodyPhotoResponse(val bodyPhotoUrl: String)

@RestController
@RequestMapping("/api/users/me")
class UserController(
    private val userService: UserService,
) {

    @GetMapping
    fun me(@AuthenticationPrincipal user: AuthenticatedUser): MeResponse {
        val found = userService.me(user.id)
        return MeResponse(
            id = requireNotNull(found.id),
            email = found.email,
            displayName = found.displayName,
            bodyPhotoUrl = mediaUrl(found.bodyPhotoPath),
        )
    }

    /**
     * 전신 사진 등록/교체. PUT 인 이유는 사용자당 한 장뿐이라 컬렉션이 아니라
     * 단일 리소스의 치환이기 때문이다.
     */
    @PutMapping("/body-photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateBodyPhoto(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestPart("image") image: MultipartFile,
    ): BodyPhotoResponse {
        val path = userService.updateBodyPhoto(user.id, image.bytes, image.contentType)
        return BodyPhotoResponse(requireNotNull(mediaUrl(path)))
    }
}

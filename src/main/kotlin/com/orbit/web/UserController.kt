package com.orbit.web

import com.orbit.security.AuthenticatedUser
import com.orbit.service.UserService
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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

/** 설정한 적이 없으면 `preference` 는 빈 문자열이 아니라 null. */
data class StylePreferenceResponse(val preference: String?)

data class UpdateStylePreferenceRequest(val preference: String?)

data class DisplayNameResponse(val displayName: String)

data class UpdateDisplayNameRequest(val displayName: String = "")

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

    /** 전신 사진은 사용자당 한 장이라 PUT 으로 교체한다. */
    @PutMapping("/body-photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateBodyPhoto(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestPart("image") image: MultipartFile,
    ): BodyPhotoResponse {
        val path = userService.updateBodyPhoto(user.id, image.bytes, image.contentType)
        return BodyPhotoResponse(requireNotNull(mediaUrl(path)))
    }

    /** 빈 이름은 400 으로 거절한다. */
    @PutMapping("/display-name")
    fun updateDisplayName(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody request: UpdateDisplayNameRequest,
    ): DisplayNameResponse =
        DisplayNameResponse(userService.updateDisplayName(user.id, request.displayName))

    /** 이 값은 [com.orbit.ai.gemini.GeminiOutfitRecommender] 프롬프트에 들어간다. */
    @GetMapping("/style-preference")
    fun stylePreference(@AuthenticationPrincipal user: AuthenticatedUser): StylePreferenceResponse =
        StylePreferenceResponse(userService.me(user.id).stylePreference)

    /** 빈 문자열을 보내면 지워지고 응답 값은 null. */
    @PutMapping("/style-preference")
    fun updateStylePreference(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody request: UpdateStylePreferenceRequest,
    ): StylePreferenceResponse =
        StylePreferenceResponse(userService.updateStylePreference(user.id, request.preference))
}

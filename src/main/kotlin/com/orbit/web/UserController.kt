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

/**
 * 스타일 선호도. 설정한 적이 없으면 `preference` 는 null 이다.
 *
 * 없을 때 빈 문자열이 아니라 null 을 주는 이유: 빈 문자열을 쓰면 "저장된 값이
 * 정말 빈 문자열인지, 아직 설정을 안 한 건지"가 서버 쪽에서도 흐려진다.
 * 없는 것은 null 하나로 표현한다.
 */
data class StylePreferenceResponse(val preference: String?)

data class UpdateStylePreferenceRequest(val preference: String?)

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

    /**
     * 추천에 반영할 취향 한 문장. 이 값은 저장에서 끝나지 않고
     * [com.orbit.ai.gemini.GeminiOutfitRecommender] 의 프롬프트로 들어간다.
     */
    @GetMapping("/style-preference")
    fun stylePreference(@AuthenticationPrincipal user: AuthenticatedUser): StylePreferenceResponse =
        StylePreferenceResponse(userService.me(user.id).stylePreference)

    /**
     * PUT 인 이유는 전신 사진과 같다 — 사용자당 값이 하나뿐이라 컬렉션에 더하는 것이
     * 아니라 단일 리소스의 치환이다. 빈 문자열을 보내면 지워지고 응답의 값은 null 이다.
     */
    @PutMapping("/style-preference")
    fun updateStylePreference(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody request: UpdateStylePreferenceRequest,
    ): StylePreferenceResponse =
        StylePreferenceResponse(userService.updateStylePreference(user.id, request.preference))
}

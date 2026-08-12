package com.orbit.web

import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.security.AuthenticatedUser
import com.orbit.service.CoordinationService
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CreateCoordinationRequest(
    @field:NotBlank val title: String,
    @field:NotEmpty val clothesIds: List<Long>,
)

data class CoordinationItemResponse(
    val clothesId: Long,
    val name: String,
    val mainCategory: MainCategory,
    val layerOrder: Int,
)

data class CoordinationResponse(
    val id: Long,
    val title: String,
    val createdAt: Instant,
    val items: List<CoordinationItemResponse>,
) {
    companion object {
        fun from(c: Coordination) = CoordinationResponse(
            id = requireNotNull(c.id),
            title = c.title,
            createdAt = c.createdAt,
            items = c.items.map {
                CoordinationItemResponse(
                    clothesId = requireNotNull(it.clothes.id),
                    name = it.clothes.name,
                    mainCategory = it.clothes.mainCategory,
                    layerOrder = it.layerOrder,
                )
            },
        )
    }
}

/**
 * 중복 조합 응답. `retry: true` 는 "같은 요청을 그대로 다시 보내지 말고
 * 다른 조합으로 재시도하라"는 신호다. 클라이언트와의 계약이므로 필드명을 바꾸면
 * 프로토콜이 깨진다.
 */
data class DuplicateResponse(
    val error: String = "duplicate",
    val retry: Boolean = true,
    val clothesIds: Set<Long>,
)

@RestController
@RequestMapping("/api/coordinations")
@Validated
class CoordinationController(
    private val service: CoordinationService,
) {

    /**
     * 소유자를 `X-Owner-Id` 헤더가 아니라 검증된 토큰에서 받는다.
     * 헤더 방식은 값을 보내는 쪽이 마음대로 바꿀 수 있어 사실상 인증이 아니었다.
     */
    @PostMapping
    fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody request: CreateCoordinationRequest,
    ): ResponseEntity<CoordinationResponse> {
        val created = service.create(user.id, request.title, request.clothesIds)
        return ResponseEntity.status(HttpStatus.CREATED).body(CoordinationResponse.from(created))
    }

    @GetMapping("/today")
    fun today(@AuthenticationPrincipal user: AuthenticatedUser): List<CoordinationResponse> =
        service.todayCoordinations(user.id).map(CoordinationResponse::from)
}

package com.orbit.web

import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.service.CoordinationService
import com.orbit.service.DuplicateCoordinationException
import com.orbit.service.UnknownClothesException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

data class ErrorResponse(val error: String, val detail: String)

@RestController
@RequestMapping("/api/coordinations")
@Validated
class CoordinationController(
    private val service: CoordinationService,
) {

    @PostMapping
    fun create(
        @RequestHeader("X-Owner-Id") ownerId: Long,
        @RequestBody request: CreateCoordinationRequest,
    ): ResponseEntity<CoordinationResponse> {
        val created = service.create(ownerId, request.title, request.clothesIds)
        return ResponseEntity.status(HttpStatus.CREATED).body(CoordinationResponse.from(created))
    }

    @GetMapping("/today")
    fun today(@RequestHeader("X-Owner-Id") ownerId: Long): List<CoordinationResponse> =
        service.todayCoordinations(ownerId).map(CoordinationResponse::from)
}

/**
 * 예외 → HTTP 응답 변환을 한 곳에 모은다. Django 버전에서는 뷰마다 try/except 를
 * 반복해 응답 모양이 조금씩 달라졌고, 결국 클라이언트가 여러 키를 fallback 으로
 * 시도하는 코드가 생겼다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DuplicateCoordinationException::class)
    fun handleDuplicate(e: DuplicateCoordinationException): ResponseEntity<DuplicateResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(DuplicateResponse(clothesIds = e.clothesIds))

    @ExceptionHandler(UnknownClothesException::class)
    fun handleUnknown(e: UnknownClothesException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("unknown_clothes", "알 수 없는 의류 id: ${e.missingIds}"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("invalid_request", e.message ?: "잘못된 요청입니다"))
}

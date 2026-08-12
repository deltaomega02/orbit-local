package com.orbit.web

import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
import com.orbit.security.AuthenticatedUser
import com.orbit.service.ClothesService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CreateClothesRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
    val mainCategory: MainCategory,
    @field:Size(max = 30) val color: String? = null,
)

/** PATCH. 넘어오지 않은(=null) 필드는 건드리지 않는다. */
data class UpdateClothesRequest(
    @field:Size(min = 1, max = 60) val name: String? = null,
    val mainCategory: MainCategory? = null,
    @field:Size(max = 30) val color: String? = null,
)

data class ClothesResponse(
    val id: Long,
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: Clothes) = ClothesResponse(
            id = requireNotNull(c.id),
            name = c.name,
            mainCategory = c.mainCategory,
            color = c.color,
            createdAt = c.createdAt,
        )
    }
}

/**
 * 페이지 응답을 직접 정의한다. Spring 의 `Page` 를 그대로 직렬화하면 내부 구조가
 * 그대로 응답 스키마가 되어(`pageable`, `sort`, `numberOfElements` …) 라이브러리를
 * 올릴 때 클라이언트가 깨진다. Boot 3.3 부터 경고가 나오는 것도 같은 이유다.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
) {
    companion object {
        fun <E, T> from(page: Page<E>, map: (E) -> T) = PageResponse(
            content = page.content.map(map),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            hasNext = page.hasNext(),
        )
    }
}

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

/**
 * 의류 CRUD.
 *
 * 모든 메서드가 `@AuthenticationPrincipal` 로 주체를 받고, 서비스에 `user.id` 를
 * 넘긴다. 헤더를 읽거나 토큰을 파싱하는 코드는 이 파일에 한 줄도 없다 —
 * 그 일은 [com.orbit.security.JwtAuthenticationFilter] 가 이미 끝냈다.
 */
@RestController
@RequestMapping("/api/clothes")
class ClothesController(
    private val service: ClothesService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: CreateClothesRequest,
    ): ResponseEntity<ClothesResponse> {
        val created = service.create(user.id, request.name, request.mainCategory, request.color)
        return ResponseEntity.status(HttpStatus.CREATED).body(ClothesResponse.from(created))
    }

    /**
     * 목록. size 상한을 두지 않으면 `?size=1000000` 한 방으로 전체를 긁어갈 수 있어
     * 페이지네이션이 무의미해진다.
     */
    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): PageResponse<ClothesResponse> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        return PageResponse.from(service.list(user.id, pageable), ClothesResponse::from)
    }

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ClothesResponse = ClothesResponse.from(service.get(user.id, id))

    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateClothesRequest,
    ): ClothesResponse =
        ClothesResponse.from(service.update(user.id, id, request.name, request.mainCategory, request.color))

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(user.id, id)
        return ResponseEntity.noContent().build()
    }
}

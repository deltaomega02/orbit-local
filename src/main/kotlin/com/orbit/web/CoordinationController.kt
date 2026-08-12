package com.orbit.web

import com.orbit.domain.Coordination
import com.orbit.domain.MainCategory
import com.orbit.security.AuthenticatedUser
import com.orbit.service.CoordinationService
import com.orbit.service.OutfitAiService
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.data.domain.PageRequest
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
    val imageUrl: String?,
)

data class CoordinationResponse(
    val id: Long,
    val title: String,
    /** AI 추천이면 추천 이유, 수동 생성이면 null. */
    val reason: String?,
    val createdAt: Instant,
    val items: List<CoordinationItemResponse>,
    /** 아직 만들지 않았으면 null. 만들려면 `POST /api/coordinations/{id}/tryon`. */
    val tryOnImageUrl: String?,
    /** 즐겨찾기 여부. 토글은 `POST /api/coordinations/{id}/favorite`. */
    val favorite: Boolean,
) {
    companion object {
        fun from(c: Coordination) = CoordinationResponse(
            id = requireNotNull(c.id),
            title = c.title,
            reason = c.reason,
            createdAt = c.createdAt,
            items = c.items.map {
                CoordinationItemResponse(
                    clothesId = requireNotNull(it.clothes.id),
                    name = it.clothes.name,
                    mainCategory = it.clothes.mainCategory,
                    layerOrder = it.layerOrder,
                    imageUrl = mediaUrl(it.clothes.imagePath),
                )
            },
            tryOnImageUrl = mediaUrl(c.tryOnImagePath),
            favorite = c.favorite,
        )
    }
}

data class TryOnResponse(val tryOnImageUrl: String)

/** 즐겨찾기 토글 결과. 클라이언트는 이 값으로 별 아이콘 상태를 맞춘다. */
data class FavoriteResponse(val favorite: Boolean)

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
    private val outfitAiService: OutfitAiService,
) {

    /**
     * AI 추천. 본문이 없다 — 서버가 옷장 전체를 후보로 삼기 때문에 클라이언트가
     * 보낼 값이 없다. 응답 계약은 수동 생성과 완전히 같고, 중복이면 여기서도
     * 409 + `retry: true` 가 나간다. 클라이언트 입장에서는 "추천을 다시 눌러라"
     * 하나로 처리가 끝난다.
     */
    @PostMapping("/recommend")
    fun recommend(@AuthenticationPrincipal user: AuthenticatedUser): ResponseEntity<CoordinationResponse> {
        val created = outfitAiService.recommend(user.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CoordinationResponse.from(created))
    }

    /**
     * 가상 착용 이미지 생성. 멱등이다 — 이미 있으면 만들지 않고 기존 것을 돌려주므로
     * 클라이언트가 재시도·중복 클릭을 신경 쓰지 않아도 된다. 그래서 201 이 아니라 200 이다.
     */
    @PostMapping("/{id}/tryon")
    fun tryOn(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): TryOnResponse = TryOnResponse(requireNotNull(mediaUrl(outfitAiService.generateTryOn(user.id, id))))

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

    /**
     * 전체 기록. 최신순.
     *
     * `/today` 와 달리 페이지네이션이 있다. 오늘 만든 코디는 많아야 몇 건이지만
     * 전체 기록은 매일 쌓여서 상한이 없기 때문이다. size 상한도 `/api/clothes` 와
     * 같은 값으로 맞춰 둔다 — 목록마다 상한이 다르면 클라이언트가 그걸 또 외워야 한다.
     */
    @GetMapping
    fun history(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): PageResponse<CoordinationResponse> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        return PageResponse.from(service.history(user.id, pageable), CoordinationResponse::from)
    }

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): CoordinationResponse = CoordinationResponse.from(service.get(user.id, id))

    /**
     * 삭제. 가상 착용 이미지 파일도 함께 지운다([CoordinationService.delete]).
     * 남의 코디와 없는 코디는 똑같이 404 다.
     */
    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(user.id, id)
        return ResponseEntity.noContent().build()
    }

    /**
     * 즐겨찾기 토글. 본문이 없다 — 서버가 현재 값을 뒤집으므로 클라이언트가
     * 보낼 값이 없다. 두 번 누르면 반드시 원래대로 돌아온다.
     *
     * POST 인 이유: 같은 요청을 두 번 보내면 결과가 달라지므로 멱등하지 않다.
     * PUT 은 멱등을 약속하는 메서드라 여기에 쓰면 거짓말이 된다.
     */
    @PostMapping("/{id}/favorite")
    fun toggleFavorite(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): FavoriteResponse = FavoriteResponse(service.toggleFavorite(user.id, id))
}

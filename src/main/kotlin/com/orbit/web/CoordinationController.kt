package com.orbit.web

import com.orbit.domain.Coordination
import com.orbit.domain.CoordinationLimits
import com.orbit.domain.MainCategory
import com.orbit.security.AuthenticatedUser
import com.orbit.service.CoordinationService
import com.orbit.service.OutfitAiService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CreateCoordinationRequest(
    // 기본 문구는 JVM 로케일을 따르므로 메시지를 직접 적는다
    @field:NotBlank(message = "コーデの名前を入力してください") val title: String,
    @field:NotEmpty(message = "服を1点以上選んでください") val clothesIds: List<Long>,
)

/** 추천 요청 본문. 본문이 없거나 `{}` 여도 된다. */
data class RecommendCoordinationRequest(
    /** 오늘의 상황 한 줄("비 오고 쌀쌀해"). 매번 프롬프트에 실리므로 길이 상한을 둔다. */
    @field:Size(max = CoordinationLimits.SITUATION, message = "今日の状況は{max}文字までです")
    val situation: String? = null,
)

data class CoordinationItemResponse(
    val clothesId: Long,
    val name: String,
    val mainCategory: MainCategory,
    val layerOrder: Int,
    val imageUrl: String?,
    /** 소프트 삭제된 옷이면 false. 화면은 이때 상세 링크를 걸지 않는다(상세는 404). */
    val inWardrobe: Boolean,
)

data class CoordinationResponse(
    val id: Long,
    /**
     * 화면의 `LOOK 014` 번호. 사용자별 순번이며 생성 시 정해져 바뀌지 않는다.
     * 지운 코디의 번호는 재사용하지 않으므로 중간이 비어 있을 수 있다.
     */
    val lookNo: Int,
    val title: String,
    /** AI 추천이면 추천 이유, 수동 생성이면 null. */
    val reason: String?,
    /** 추천 요청 시 적은 오늘의 상황. 없거나 수동 생성이면 null. */
    val situation: String?,
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
            // 생성 트랜잭션과 LookNumberBackfill 이 채우므로 null 이면 버그로 보고 실패시킨다
            lookNo = requireNotNull(c.lookNo) { "LOOK 번호가 없는 코디입니다: ${c.id}" },
            title = c.title,
            reason = c.reason,
            situation = c.situation,
            createdAt = c.createdAt,
            items = c.items.map {
                CoordinationItemResponse(
                    clothesId = requireNotNull(it.clothes.id),
                    name = it.clothes.name,
                    mainCategory = it.clothes.mainCategory,
                    layerOrder = it.layerOrder,
                    imageUrl = mediaUrl(it.clothes.imagePath),
                    inWardrobe = it.clothes.deletedAt == null,
                )
            },
            tryOnImageUrl = mediaUrl(c.tryOnImagePath),
            favorite = c.favorite,
        )
    }
}

data class TryOnResponse(val tryOnImageUrl: String)

data class FavoriteResponse(val favorite: Boolean)

/** 409 `duplicate`, `retry: true`(다시 요청하면 다른 조합이 나올 수 있음). 필드명은 클라이언트 계약이다. */
data class DuplicateResponse(
    val error: String = "duplicate",
    val retry: Boolean = true,
    val clothesIds: Set<Long>,
)

/**
 * 409 `exhausted`, `retry: false`. 오늘 만들 수 있는 조합을 다 썼음(옷 추가 또는 내일 다시).
 * 409 `duplicate` 는 오늘 나온 조합과 겹친 경우라 재시도하면 된다.
 * `detail` 은 화면에 그대로 띄우는 일본어 문장.
 */
data class ExhaustedResponse(
    val error: String = "exhausted",
    val retry: Boolean = false,
    val detail: String,
)

@RestController
@RequestMapping("/api/coordinations")
@Validated
class CoordinationController(
    private val service: CoordinationService,
    private val outfitAiService: OutfitAiService,
) {

    /**
     * 옷장 전체가 후보인 AI 추천. 빈 본문·`{}`·공백 situation 은 모두 상황 없음. 응답은 수동 생성과 같다.
     * 409 `duplicate`(retry: true) / 409 `exhausted`(retry: false, AI 호출 전에 판정)
     * / 502 `ai_invalid_response`(없는 옷 id 등으로 AI 응답 거절).
     */
    @PostMapping("/recommend")
    fun recommend(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody(required = false) request: RecommendCoordinationRequest?,
    ): ResponseEntity<CoordinationResponse> {
        val created = outfitAiService.recommend(user.id, request?.situation)
        return ResponseEntity.status(HttpStatus.CREATED).body(CoordinationResponse.from(created))
    }

    /** 가상 착용 이미지 생성. 이미 있으면 기존 것을 돌려주는 멱등 호출이라 200. */
    @PostMapping("/{id}/tryon")
    fun tryOn(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): TryOnResponse = TryOnResponse(requireNotNull(mediaUrl(outfitAiService.generateTryOn(user.id, id))))

    /**
     * 가상 착용 이미지만 삭제하고 코디는 남긴다. 삭제 후 `POST /{id}/tryon` 으로 다시 생성할 수 있다.
     * 지울 이미지가 없어도 204, 남의 코디나 없는 코디는 404.
     */
    @DeleteMapping("/{id}/tryon")
    fun deleteTryOn(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.deleteTryOn(user.id, id)
        return ResponseEntity.noContent().build()
    }

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

    /** 전체 기록, 최신순. */
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

    /** 가상 착용 이미지 파일도 함께 지운다. 남의 코디나 없는 코디는 404. */
    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(user.id, id)
        return ResponseEntity.noContent().build()
    }

    /** 본문 없이 현재 값을 뒤집는다. 멱등하지 않으므로 PUT 이 아니라 POST. */
    @PostMapping("/{id}/favorite")
    fun toggleFavorite(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): FavoriteResponse = FavoriteResponse(service.toggleFavorite(user.id, id))
}

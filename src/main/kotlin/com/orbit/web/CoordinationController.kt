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
    /**
     * 이 옷이 아직 옷장에 있는가.
     *
     * 옷을 지워도 과거 코디에는 "그때 입은 옷"으로 남는다(소프트 삭제). 그런데 서버가
     * 그 사실을 알려주지 않으면 화면은 지워진 옷에도 상세 링크를 걸고, 누르면 404 로
     * 빠져 되돌아올 수 없는 막다른 화면이 된다. 실제로 그랬다.
     * 화면은 이 값이 false 면 링크를 걸지 않고 "옷장에서 지움"으로 표시한다.
     */
    val inWardrobe: Boolean,
)

data class CoordinationResponse(
    val id: Long,
    /**
     * 화면에 `LOOK 014` 로 찍히는 번호. **사용자별 순번이며 생성 시 정해져 바뀌지 않는다.**
     *
     * 이 필드가 따로 있는 이유는 화면이 [id] 를 세 자리로 채워 번호를 만들고 있었기
     * 때문이다. id 는 전역 시퀀스라 사용자가 둘이면 내 첫 코디가 LOOK 037 이 되고,
     * 애초에 사용자에게 보여줄 뜻을 가진 값이 아니다. 표시용 번호는 서버가 저장해
     * 내려준다 — 화면이 목록 순서나 id 로 다시 계산하면 코디를 지웠을 때 번호가 밀린다.
     *
     * 번호는 **비어 있을 수 있다**(1, 3, 4 …). 지운 코디의 번호는 다시 쓰지 않는다.
     * 근거는 [com.orbit.domain.Coordination.lookNo] 주석에 적어 뒀다.
     */
    val lookNo: Int,
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
            // 저장된 코디에는 번호가 반드시 있다. 부여 지점이 생성 트랜잭션 하나뿐이고,
            // 그 이전에 만들어진 행은 기동 시 LookNumberBackfill 이 채운다. 그래도 null 이면
            // 둘 중 하나가 깨진 것이므로, 0 이나 id 로 얼버무리지 않고 여기서 드러낸다.
            lookNo = requireNotNull(c.lookNo) { "LOOK 번호가 없는 코디입니다: ${c.id}" },
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
                    inWardrobe = it.clothes.deletedAt == null,
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
     * 가상 착용 이미지만 삭제. 코디는 그대로 남고 `tryOnImageUrl` 이 null 로 돌아간다.
     *
     * 지울 이미지가 없어도 **204** 다 — 결과 상태("이 코디에 가상 착용 이미지가 없다")가
     * 같기 때문이다. 이미지가 없다고 404 를 주면 클라이언트가 지우기 전에 상태를 한 번
     * 더 조회해야 하고, 두 번 누른 사용자에게 실패처럼 보인다. 반대로 **남의 코디와 없는
     * 코디는 404** 다. 그건 "이미 그 상태"가 아니라 애초에 다룰 자격이 없는 자원이다.
     *
     * 삭제 후 다시 `POST /{id}/tryon` 을 부르면 새로 생성된다. 생성이 멱등이라
     * (이미 있으면 만들지 않는다) 이 엔드포인트가 없으면 한 번 잘못 나온 결과를
     * 되돌릴 방법이 룩 전체 삭제뿐이었다.
     */
    @DeleteMapping("/{id}/tryon")
    fun deleteTryOn(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.deleteTryOn(user.id, id)
        return ResponseEntity.noContent().build()
    }

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

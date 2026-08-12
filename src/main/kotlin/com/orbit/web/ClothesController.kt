package com.orbit.web

import com.orbit.domain.Clothes
import com.orbit.domain.MainCategory
import com.orbit.media.MediaStorage
import com.orbit.security.AuthenticatedUser
import com.orbit.service.ClothesService
import com.orbit.service.CoordinationService
import com.orbit.service.OutfitAiService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

data class CreateClothesRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
    val mainCategory: MainCategory,
    @field:Size(max = 30) val color: String? = null,
    @field:Size(max = 200) val detail: String? = null,
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
    /** 소재·핏·상황. 추천 품질에 직접 쓰이므로 응답에도 노출해 사용자가 고칠 수 있게 한다. */
    val detail: String?,
    /** 사진이 없으면 null. 클라이언트는 이 값으로 플레이스홀더 표시 여부를 판단한다. */
    val imageUrl: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: Clothes) = ClothesResponse(
            id = requireNotNull(c.id),
            name = c.name,
            mainCategory = c.mainCategory,
            color = c.color,
            detail = c.detail,
            imageUrl = mediaUrl(c.imagePath),
            createdAt = c.createdAt,
        )
    }
}

/** 통계의 "많이 입은 옷" 한 줄. */
data class ClothesUsageResponse(
    val clothesId: Long,
    val name: String,
    val imageUrl: String?,
    val usedCount: Long,
)

/**
 * 옷장 통계.
 *
 * `byCategory` 의 키를 [MainCategory] 이름 문자열로 두고 **값이 0인 카테고리는
 * 넣지 않는다.** 옷이 하나도 없는 카테고리를 0으로 채워 보내면 화면이 빈 막대를
 * 그리게 되는데, 그건 서버가 아니라 화면이 정할 일이다.
 *
 * `neverUsed` 는 개수만 준다. 어떤 옷인지까지 주려면 목록이 되고, 목록이면
 * 페이지네이션이 필요해진다. "안 입은 옷이 3벌 있다"는 신호가 통계의 역할이고,
 * 그 다음은 옷장 목록에서 볼 일이다.
 */
data class WardrobeStatsResponse(
    val total: Long,
    val byCategory: Map<String, Long>,
    val mostUsed: List<ClothesUsageResponse>,
    val neverUsed: Long,
)

/** 사진 분석 결과. 저장하지 않고 등록 폼을 미리 채우는 데만 쓴다. */
data class ClothesAnalysisResponse(
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val detail: String?,
)

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

/**
 * 목록 API 들이 공유하는 페이지 크기. 엔드포인트마다 기본값과 상한이 다르면
 * 클라이언트가 그걸 전부 외워야 한다. 상한이 없으면 `?size=1000000` 한 번으로
 * 전체를 긁어갈 수 있어 페이지네이션 자체가 무의미해진다.
 */
internal const val DEFAULT_PAGE_SIZE = 20
internal const val MAX_PAGE_SIZE = 100

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
    private val coordinationService: CoordinationService,
    private val outfitAiService: OutfitAiService,
    private val mediaStorage: MediaStorage,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: CreateClothesRequest,
    ): ResponseEntity<ClothesResponse> {
        val created = service.create(user.id, request.name, request.mainCategory, request.color, null, request.detail)
        return ResponseEntity.status(HttpStatus.CREATED).body(ClothesResponse.from(created))
    }

    /**
     * 사진과 함께 등록한다. JSON 버전과 경로를 공유하고 Content-Type 으로 갈린다 —
     * 사진 없이 이름만 넣는 기존 클라이언트를 깨지 않으면서 새 경로를 더하기 위해서다.
     *
     * 파일을 먼저 쓰고 DB 행을 만든다. DB 저장이 실패하면 아무도 참조하지 않는 파일이
     * 하나 남지만, 반대 순서였다면 사진이 보이지 않는 행이 남는다. 사용자에게 보이는
     * 고장은 뒤쪽이 훨씬 나쁘다.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createWithImage(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam("name") name: String,
        @RequestParam("mainCategory") mainCategory: MainCategory,
        @RequestParam("color", required = false) color: String?,
        @RequestParam("detail", required = false) detail: String?,
        @RequestPart("image", required = false) image: MultipartFile?,
    ): ResponseEntity<ClothesResponse> {
        // multipart 폼 필드에는 @Valid 가 걸리지 않으므로 JSON 쪽과 같은 제약을 손으로 맞춘다.
        require(name.isNotBlank() && name.length <= 60) { "name 은 1~60자여야 합니다" }
        require((color?.length ?: 0) <= 30) { "color 는 30자를 넘을 수 없습니다" }

        val imagePath = image?.takeIf { !it.isEmpty }
            ?.let { mediaStorage.store("clothes", it.bytes, it.contentType).relativePath }
        val created = service.create(user.id, name, mainCategory, color, imagePath, detail)
        return ResponseEntity.status(HttpStatus.CREATED).body(ClothesResponse.from(created))
    }

    /**
     * 사진 한 장으로 등록 값을 제안한다. **저장하지 않는다.**
     *
     * 분석과 등록을 한 번에 하지 않은 이유: AI 가 틀린 값을 넣은 채로 저장되면
     * 사용자는 그걸 다시 고쳐야 하고, 그 사이 옷장에는 잘못된 데이터가 남는다.
     * 제안 → 사람이 확인 → 저장 순서라야 AI 가 틀려도 옷장이 오염되지 않는다.
     */
    @PostMapping("/analyze", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyze(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestPart("image") image: MultipartFile,
    ): ClothesAnalysisResponse {
        // 저장하지 않더라도 크기·형식 검증은 똑같이 통과해야 한다. 검증을 건너뛰면
        // 이 엔드포인트가 "아무 바이트나 AI 에 그대로 넘기는 통로"가 된다.
        val type = mediaStorage.validate(image.bytes, image.contentType)
        val analysis = outfitAiService.analyze(image.bytes, type.mime)
        return ClothesAnalysisResponse(
            name = analysis.name,
            mainCategory = analysis.mainCategory,
            color = analysis.color,
            detail = analysis.detail,
        )
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
        @RequestParam(required = false) mainCategory: MainCategory?,
    ): PageResponse<ClothesResponse> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        return PageResponse.from(service.list(user.id, pageable, mainCategory), ClothesResponse::from)
    }

    /**
     * 옷장 통계.
     *
     * `/{id}` 보다 먼저 선언했지만 순서와는 무관하다 — Spring 은 리터럴 경로를
     * 경로 변수보다 우선해서 매칭한다. 다만 읽는 사람에게는 붙어 있는 편이 낫다.
     */
    @GetMapping("/stats")
    fun stats(@AuthenticationPrincipal user: AuthenticatedUser): WardrobeStatsResponse {
        val stats = service.stats(user.id)
        return WardrobeStatsResponse(
            total = stats.total,
            byCategory = stats.byCategory.mapKeys { it.key.name },
            mostUsed = stats.mostUsed.map {
                ClothesUsageResponse(
                    clothesId = it.clothesId,
                    name = it.name,
                    imageUrl = mediaUrl(it.imagePath),
                    usedCount = it.usedCount,
                )
            },
            neverUsed = stats.neverUsed,
        )
    }

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ClothesResponse = ClothesResponse.from(service.get(user.id, id))

    /**
     * 이 옷이 실제로 쓰인 코디들. 사용자가 옷을 눌렀을 때 "이 옷으로 뭘 입었었지"를 본다.
     *
     * 옷의 존재·소유 확인을 먼저 한다. 그러지 않으면 남의 옷 id 로 물었을 때 빈 목록이
     * 나가는데, 빈 목록은 "그 옷은 코디에 안 쓰였다"는 뜻이라 **그 id 가 실재한다는
     * 사실을 흘린다.** 없는 것과 남의 것은 똑같이 404 여야 한다.
     */
    @GetMapping("/{id}/coordinations")
    fun coordinations(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): PageResponse<CoordinationResponse> {
        service.get(user.id, id) // 없거나 남의 것이면 여기서 404
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        return PageResponse.from(
            coordinationService.byClothes(user.id, id, pageable),
            CoordinationResponse::from,
        )
    }

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

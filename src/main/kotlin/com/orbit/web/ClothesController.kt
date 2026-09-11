package com.orbit.web

import com.orbit.domain.Clothes
import com.orbit.domain.ClothesLimits
import com.orbit.domain.MainCategory
import com.orbit.media.ImageNormalizer
import com.orbit.media.MediaStorage
import com.orbit.security.AuthenticatedUser
import com.orbit.service.ClothesService
import com.orbit.service.ClothesTraits
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

// 서술 속성은 AI 가 틀릴 수 있어 전부 선택·수정 가능. 등록·수정·분석이 같은 필드 집합을 다룬다.
// 기본 검증 문구는 JVM 로케일을 따르므로 화면에 띄울 메시지를 직접 적는다.
data class CreateClothesRequest(
    @field:NotBlank(message = "服の名前を入力してください")
    @field:Size(max = ClothesLimits.NAME, message = "名前は{max}文字を超えられません")
    val name: String,
    val mainCategory: MainCategory,
    @field:Size(max = ClothesLimits.COLOR, message = "色は{max}文字を超えられません")
    val color: String? = null,
    @field:Size(max = ClothesLimits.SUB_CATEGORY, message = "種類は{max}文字を超えられません")
    val subCategory: String? = null,
    @field:Size(max = ClothesLimits.MATERIAL, message = "素材は{max}文字を超えられません")
    val material: String? = null,
    @field:Size(max = ClothesLimits.FIT, message = "フィットは{max}文字を超えられません")
    val fit: String? = null,
    @field:Size(max = ClothesLimits.SEASON, message = "季節は{max}文字を超えられません")
    val season: String? = null,
    @field:Size(max = ClothesLimits.DETAIL, message = "ひとことは{max}文字を超えられません")
    val detail: String? = null,
) {
    fun toTraits() = ClothesTraits(color, subCategory, material, fit, season, detail)
}

/** PATCH. null 필드는 유지하고 빈 문자열은 지운다(모든 속성 공통). */
data class UpdateClothesRequest(
    @field:Size(min = 1, max = ClothesLimits.NAME, message = "名前は1〜{max}文字にしてください")
    val name: String? = null,
    val mainCategory: MainCategory? = null,
    @field:Size(max = ClothesLimits.COLOR, message = "色は{max}文字を超えられません")
    val color: String? = null,
    @field:Size(max = ClothesLimits.SUB_CATEGORY, message = "種類は{max}文字を超えられません")
    val subCategory: String? = null,
    @field:Size(max = ClothesLimits.MATERIAL, message = "素材は{max}文字を超えられません")
    val material: String? = null,
    @field:Size(max = ClothesLimits.FIT, message = "フィットは{max}文字を超えられません")
    val fit: String? = null,
    @field:Size(max = ClothesLimits.SEASON, message = "季節は{max}文字を超えられません")
    val season: String? = null,
    @field:Size(max = ClothesLimits.DETAIL, message = "ひとことは{max}文字を超えられません")
    val detail: String? = null,
) {
    fun toTraits() = ClothesTraits(color, subCategory, material, fit, season, detail)
}

data class ClothesResponse(
    val id: Long,
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    // 아래 속성은 null 일 수 있다(속성 도입 전에 등록된 옷 포함). 빈 값 표시는 화면이 정한다.
    /** 셔츠·니트·청바지·코트 등 [mainCategory] 의 하위 종류. */
    val subCategory: String?,
    val material: String?,
    val fit: String?,
    /** 春・秋 / 夏 / 冬 / オールシーズン. 직접 입력하면 그 밖의 값도 들어온다([com.orbit.domain.Seasons]). */
    val season: String?,
    /** 한 줄 요약. 추천 프롬프트에 그대로 들어간다. */
    val detail: String?,
    /** 사진이 없으면 null. */
    val imageUrl: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(c: Clothes) = ClothesResponse(
            id = requireNotNull(c.id),
            name = c.name,
            mainCategory = c.mainCategory,
            color = c.color,
            subCategory = c.subCategory,
            material = c.material,
            fit = c.fit,
            season = c.season,
            detail = c.detail,
            imageUrl = mediaUrl(c.imagePath),
            createdAt = c.createdAt,
        )
    }
}

data class ClothesUsageResponse(
    val clothesId: Long,
    val name: String,
    val imageUrl: String?,
    val usedCount: Long,
)

/**
 * `byCategory` 키는 [MainCategory] 이름이며 0 인 카테고리는 넣지 않는다.
 * `neverUsed` 는 목록이 아니라 개수만 준다.
 */
data class WardrobeStatsResponse(
    val total: Long,
    val byCategory: Map<String, Long>,
    val mostUsed: List<ClothesUsageResponse>,
    val neverUsed: Long,
)

/**
 * 저장하지 않고 등록 폼을 채우는 데만 쓴다. 필드 이름은 [CreateClothesRequest] 와 맞춘다.
 * 이름·카테고리 외에는 모델이 답하지 않았거나 형식이 틀리면 null.
 */
data class ClothesAnalysisResponse(
    val name: String,
    val mainCategory: MainCategory,
    val color: String?,
    val subCategory: String?,
    val material: String?,
    val fit: String?,
    val season: String?,
    val detail: String?,
)

/** Spring `Page` 를 그대로 직렬화하면 내부 구조가 API 스키마가 되므로 직접 정의한다. */
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

// 목록 API 공통 페이지 크기. 상한이 없으면 큰 size 한 번으로 전체를 가져갈 수 있다.
internal const val DEFAULT_PAGE_SIZE = 20
internal const val MAX_PAGE_SIZE = 100

@RestController
@RequestMapping("/api/clothes")
class ClothesController(
    private val service: ClothesService,
    private val coordinationService: CoordinationService,
    private val outfitAiService: OutfitAiService,
    private val mediaStorage: MediaStorage,
    private val imageNormalizer: ImageNormalizer,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: CreateClothesRequest,
    ): ResponseEntity<ClothesResponse> {
        val created = service.create(user.id, request.name, request.mainCategory, request.toTraits())
        return ResponseEntity.status(HttpStatus.CREATED).body(ClothesResponse.from(created))
    }

    /**
     * JSON 등록과 같은 경로이고 Content-Type 으로 구분한다.
     * 사진 없는 행보다 고아 파일이 낫기 때문에 파일을 먼저 쓰고 DB 행을 만든다.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createWithImage(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam("name") name: String,
        @RequestParam("mainCategory") mainCategory: MainCategory,
        @RequestParam("color", required = false) color: String?,
        @RequestParam("subCategory", required = false) subCategory: String?,
        @RequestParam("material", required = false) material: String?,
        @RequestParam("fit", required = false) fit: String?,
        @RequestParam("season", required = false) season: String?,
        @RequestParam("detail", required = false) detail: String?,
        @RequestPart("image", required = false) image: MultipartFile?,
    ): ResponseEntity<ClothesResponse> {
        // multipart 필드에는 @Valid 가 걸리지 않으므로 같은 제약을 직접 검사한다.
        // 상한은 컬럼·@Size 와 어긋나지 않도록 ClothesLimits 에서만 가져온다.
        require(name.isNotBlank() && name.length <= ClothesLimits.NAME) { "name は1〜${ClothesLimits.NAME}文字にしてください" }
        requireMaxLength("color", color, ClothesLimits.COLOR)
        requireMaxLength("subCategory", subCategory, ClothesLimits.SUB_CATEGORY)
        requireMaxLength("material", material, ClothesLimits.MATERIAL)
        requireMaxLength("fit", fit, ClothesLimits.FIT)
        requireMaxLength("season", season, ClothesLimits.SEASON)
        requireMaxLength("detail", detail, ClothesLimits.DETAIL)

        val imagePath = image?.takeIf { !it.isEmpty }
            ?.let { mediaStorage.store("clothes", it.bytes, it.contentType).relativePath }
        val created = service.create(
            ownerId = user.id,
            name = name,
            mainCategory = mainCategory,
            traits = ClothesTraits(color, subCategory, material, fit, season, detail),
            imagePath = imagePath,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ClothesResponse.from(created))
    }

    /** 메시지는 그대로 응답 `detail` 이 되므로 일본어로 쓰고, 필드명은 API 이름을 쓴다. */
    private fun requireMaxLength(field: String, value: String?, max: Int) {
        require((value?.length ?: 0) <= max) { "$field は${max}文字を超えられません" }
    }

    /** 사진으로 등록 값을 제안만 하고 저장하지 않는다. 사용자가 확인한 뒤 등록한다. */
    @PostMapping("/analyze", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyze(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestPart("image") image: MultipartFile,
    ): ClothesAnalysisResponse {
        // 저장하지 않아도 아무 바이트나 AI 로 넘기지 않도록 형식 검증은 한다
        val type = mediaStorage.validate(image.bytes, image.contentType)
        // base64 로 부풀어 요청 크기·토큰 비용이 되므로 줄이고, EXIF 회전도 적용한다
        val payload = imageNormalizer.forAiPayload(image.bytes, type)
        val analysis = outfitAiService.analyze(payload.bytes, payload.type.mime)
        return ClothesAnalysisResponse(
            name = analysis.name,
            mainCategory = analysis.mainCategory,
            color = analysis.color,
            subCategory = analysis.subCategory,
            material = analysis.material,
            fit = analysis.fit,
            season = analysis.season,
            detail = analysis.detail,
        )
    }

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
     * 이 옷이 쓰인 코디 목록.
     * 빈 목록으로 id 존재가 드러나지 않도록 소유 확인을 먼저 해 없거나 남의 옷이면 404.
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
        ClothesResponse.from(
            service.update(user.id, id, request.name, request.mainCategory, request.toTraits()),
        )

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(user.id, id)
        return ResponseEntity.noContent().build()
    }
}

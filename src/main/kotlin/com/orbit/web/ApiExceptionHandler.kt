package com.orbit.web

import com.orbit.ai.AiCallFailedException
import com.orbit.ai.AiInvalidResponseException
import com.orbit.ai.AiUnavailableException
import com.orbit.media.ImageTooLargeException
import com.orbit.media.UnsupportedImageTypeException
import com.orbit.security.InvalidTokenException
import com.orbit.service.ClothesInUseException
import com.orbit.service.ClothesNotFoundException
import com.orbit.service.CoordinationNotFoundException
import com.orbit.service.DuplicateCoordinationException
import com.orbit.service.EmailAlreadyUsedException
import com.orbit.service.InvalidCredentialsException
import com.orbit.service.NoBodyPhotoException
import com.orbit.service.NotEnoughClothesException
import com.orbit.service.UnknownClothesException
import com.orbit.service.UserNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

data class ErrorResponse(val error: String, val detail: String)

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

    @ExceptionHandler(ClothesNotFoundException::class)
    fun handleClothesNotFound(e: ClothesNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "의류를 찾을 수 없습니다"))

    @ExceptionHandler(ClothesInUseException::class)
    fun handleClothesInUse(e: ClothesInUseException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("clothes_in_use", "코디에 사용 중인 의류는 삭제할 수 없습니다"))

    @ExceptionHandler(EmailAlreadyUsedException::class)
    fun handleEmailTaken(e: EmailAlreadyUsedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("duplicate_email", "이미 가입된 이메일입니다"))

    /**
     * 로그인 실패와 토큰 오류는 모두 401 + 뭉뚱그린 메시지다. "이메일이 없습니다"와
     * "비밀번호가 틀렸습니다"를 구분해 주면 그 자체가 가입 여부 조회 API 가 된다.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_credentials", "이메일 또는 비밀번호가 올바르지 않습니다"))

    @ExceptionHandler(InvalidGeminiKeyException::class)
    fun handleInvalidGeminiKey(e: InvalidGeminiKeyException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("invalid_key", "키가 거절되었습니다. 값을 다시 확인해 주세요."))

    /**
     * 입력 검증 실패. 어느 필드가 왜 걸렸는지까지 돌려준다.
     * 프레임워크 기본 응답을 그대로 내보내면 timestamp·path 같은 내부 형식이
     * 그대로 API 스키마가 되고, 클라이언트는 우리 오류와 다른 모양을 하나 더
     * 다뤄야 한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val detail = e.bindingResult.fieldErrors
            .joinToString(" ") { it.defaultMessage ?: "${it.field} 값이 올바르지 않습니다." }
            .ifBlank { "입력값을 확인해 주세요." }
        return ResponseEntity.badRequest().body(ErrorResponse("invalid_request", detail))
    }

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(e: InvalidTokenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_token", "유효하지 않은 토큰입니다"))

    // ── 이미지 업로드 ──────────────────────────────────────────────

    @ExceptionHandler(UnsupportedImageTypeException::class)
    fun handleUnsupportedImage(e: UnsupportedImageTypeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorResponse("unsupported_image_type", "jpeg/png/webp 이미지만 업로드할 수 있습니다"))

    /**
     * 크기 초과는 두 경로로 들어온다. 애플리케이션 상한([ImageTooLargeException])과
     * 서블릿 컨테이너 상한(MaxUploadSizeExceededException). 컨테이너 쪽은 요청을 다 읽기
     * 전에 끊어주므로 실제 DoS 방어는 그쪽이 하고, 애플리케이션 상한이 그 뒤를 받친다.
     * 응답 모양은 같아야 하므로 두 예외를 같은 error 코드로 모은다.
     */
    @ExceptionHandler(ImageTooLargeException::class)
    fun handleImageTooLarge(e: ImageTooLargeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse("image_too_large", "이미지 상한은 ${e.maxBytes}바이트입니다"))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse("image_too_large", "업로드 크기 상한을 초과했습니다"))

    // ── AI ────────────────────────────────────────────────────────

    /**
     * 503. AI 를 못 쓰는 것은 서버가 고장 난 게 아니라 "지금 그 기능만 없는" 상태다.
     * 옷장 CRUD 는 그대로 200 을 준다. 원본 Django 도 키가 없다고 앱 전체를 죽이지는
     * 않았는데, 그 판단은 좋았으므로 그대로 가져왔다.
     */
    @ExceptionHandler(AiUnavailableException::class)
    fun handleAiUnavailable(e: AiUnavailableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("ai_unavailable", "AI 기능을 사용할 수 없습니다"))

    /** 502. 우리 잘못도 클라이언트 잘못도 아니고 상류가 실패했다는 뜻이다. */
    @ExceptionHandler(AiCallFailedException::class)
    fun handleAiFailed(e: AiCallFailedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("ai_failed", "AI 호출에 실패했습니다. 잠시 후 다시 시도해 주세요"))

    /**
     * 502. 응답은 왔지만 서버 검증에서 걸렀다(예: 존재하지 않는 clothesId).
     * 400 으로 내리지 않는 이유는 클라이언트가 보낸 것이 아무것도 없기 때문이다 —
     * 잘못은 전적으로 상류에 있고 클라이언트가 할 수 있는 건 재시도뿐이다.
     */
    @ExceptionHandler(AiInvalidResponseException::class)
    fun handleAiInvalid(e: AiInvalidResponseException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("ai_invalid_response", "AI 응답을 신뢰할 수 없어 거절했습니다"))

    @ExceptionHandler(NotEnoughClothesException::class)
    fun handleNotEnoughClothes(e: NotEnoughClothesException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("not_enough_clothes", "상의와 하의를 각각 최소 1벌 등록해 주세요"))

    @ExceptionHandler(NoBodyPhotoException::class)
    fun handleNoBodyPhoto(e: NoBodyPhotoException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("no_body_photo", "전신 사진을 먼저 등록해 주세요"))

    @ExceptionHandler(CoordinationNotFoundException::class)
    fun handleCoordinationNotFound(e: CoordinationNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "코디를 찾을 수 없습니다"))

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(e: UserNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "사용자를 찾을 수 없습니다"))
}

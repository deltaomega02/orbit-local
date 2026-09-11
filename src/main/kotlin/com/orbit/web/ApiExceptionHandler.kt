package com.orbit.web

import com.orbit.ai.AiCallFailedException
import com.orbit.ai.AiInvalidResponseException
import com.orbit.ai.AiUnavailableException
import com.orbit.media.UnsupportedImageTypeException
import com.orbit.security.InvalidTokenException
import com.orbit.service.ClothesNotFoundException
import com.orbit.service.CombinationsExhaustedException
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

/**
 * `error` 는 클라이언트가 분기하는 코드(영어 소문자 고정), `detail` 은 화면에 보여 줄 일본어 문장.
 * 클라이언트는 `detail` 로 분기하면 안 된다.
 */
data class ErrorResponse(val error: String, val detail: String)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DuplicateCoordinationException::class)
    fun handleDuplicate(e: DuplicateCoordinationException): ResponseEntity<DuplicateResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(DuplicateResponse(clothesIds = e.clothesIds))

    /** 409 `exhausted`. duplicate 와 달리 재시도해도 결과가 같으므로 error 코드를 나눈다. */
    @ExceptionHandler(CombinationsExhaustedException::class)
    fun handleExhausted(e: CombinationsExhaustedException): ResponseEntity<ExhaustedResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ExhaustedResponse(
                detail = "今日つくれる組み合わせ${e.possible}通りをすべて見ました。" +
                    "服を追加すると新しい組み合わせが出てきます。",
            ),
        )

    @ExceptionHandler(UnknownClothesException::class)
    fun handleUnknown(e: UnknownClothesException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("unknown_clothes", "見つからない服のidがあります: ${e.missingIds}"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("invalid_request", e.message ?: "リクエストが正しくありません"))

    @ExceptionHandler(ClothesNotFoundException::class)
    fun handleClothesNotFound(e: ClothesNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "服が見つかりません"))

    @ExceptionHandler(EmailAlreadyUsedException::class)
    fun handleEmailTaken(e: EmailAlreadyUsedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("duplicate_email", "すでに登録されているメールアドレスです"))

    /** 가입 여부가 드러나지 않도록 이메일 없음과 비밀번호 틀림을 구분하지 않는다. */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_credentials", "メールアドレスまたはパスワードが正しくありません"))

    @ExceptionHandler(InvalidGeminiKeyException::class)
    fun handleInvalidGeminiKey(e: InvalidGeminiKeyException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("invalid_key", "キーが拒否されました。値をもう一度確認してください。"))

    /** 프레임워크 기본 오류 형식 대신 [ErrorResponse] 로 필드 오류 메시지를 돌려준다. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val detail = e.bindingResult.fieldErrors
            .joinToString(" ") { it.defaultMessage ?: "${it.field} の値が正しくありません。" }
            .ifBlank { "入力内容を確認してください。" }
        return ResponseEntity.badRequest().body(ErrorResponse("invalid_request", detail))
    }

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(e: InvalidTokenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_token", "トークンが無効です"))

    // --- 이미지 업로드

    @ExceptionHandler(UnsupportedImageTypeException::class)
    fun handleUnsupportedImage(e: UnsupportedImageTypeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorResponse("unsupported_image_type", "jpeg/png/webp の画像だけアップロードできます"))

    /** 413 `image_too_large`. 지금은 업로드 상한이 없지만, 상한을 다시 걸면 톰캣 오류 페이지 대신 JSON 을 주기 위해 둔다. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ErrorResponse("image_too_large", "この写真は読み込めませんでした。別の写真でお試しください。"),
        )

    // --- AI

    /** 503. AI 기능만 쓸 수 없는 상태이고 옷장 CRUD 는 계속 동작한다. */
    @ExceptionHandler(AiUnavailableException::class)
    fun handleAiUnavailable(e: AiUnavailableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("ai_unavailable", "AI機能は今使えません"))

    /** 502. 상류 AI 호출 실패. */
    @ExceptionHandler(AiCallFailedException::class)
    fun handleAiFailed(e: AiCallFailedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("ai_failed", "AIの呼び出しに失敗しました。少し待ってからもう一度お試しください"))

    /** 502. AI 응답이 서버 검증에 걸린 경우(예: 없는 clothesId). 클라이언트 잘못이 아니므로 400 이 아니다. */
    @ExceptionHandler(AiInvalidResponseException::class)
    fun handleAiInvalid(e: AiInvalidResponseException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("ai_invalid_response", "AIの応答が信頼できないため受け付けませんでした"))

    @ExceptionHandler(NotEnoughClothesException::class)
    fun handleNotEnoughClothes(e: NotEnoughClothesException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("not_enough_clothes", "トップスとボトムスを最低1点ずつ登録してください"))

    @ExceptionHandler(NoBodyPhotoException::class)
    fun handleNoBodyPhoto(e: NoBodyPhotoException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("no_body_photo", "先に全身写真を登録してください"))

    @ExceptionHandler(CoordinationNotFoundException::class)
    fun handleCoordinationNotFound(e: CoordinationNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "コーデが見つかりません"))

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(e: UserNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("not_found", "ユーザーが見つかりません"))
}

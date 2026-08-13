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
 * 오류 응답. `error` 는 **클라이언트가 분기하는 코드**라 영어 소문자로 고정이고,
 * `detail` 은 **사람이 읽는 문장**이라 일본어다.
 *
 * 둘을 나눠 두는 것이 여기서 값을 한다 — 문구를 다른 언어로 갈아도 화면의 분기
 * 로직은 하나도 바뀌지 않는다. 반대로 `detail` 을 보고 분기하는 클라이언트가
 * 있었다면 이 커밋이 그 화면을 통째로 깨뜨렸을 것이다.
 */
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

    /**
     * 409 `exhausted`. duplicate 와 상태 코드는 같지만 **다른 사건**이라 error 코드를
     * 나눈다([ExhaustedResponse]). 여기서 duplicate 로 뭉뚱그리면 클라이언트는
     * "다시 시도"밖에 못 하고, 아무리 눌러도 결과가 같은 상태에서 계속 누르게 된다.
     */
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

    /*
     * `clothes_in_use` (409) 는 없앴다.
     *
     * 코디에 쓰인 옷의 삭제를 거절하는 대신 소프트 삭제로 처리하도록 규칙을
     * 바꿨기 때문이다([com.orbit.domain.Clothes.deletedAt]). 지금은 쓰인 적이 있든
     * 없든 삭제가 성공하고 204 가 나간다. 클라이언트에서 이 오류를 다루던 분기는
     * 도달할 수 없는 코드가 됐다.
     */

    @ExceptionHandler(EmailAlreadyUsedException::class)
    fun handleEmailTaken(e: EmailAlreadyUsedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("duplicate_email", "すでに登録されているメールアドレスです"))

    /**
     * 로그인 실패와 토큰 오류는 모두 401 + 뭉뚱그린 메시지다. "이메일이 없습니다"와
     * "비밀번호가 틀렸습니다"를 구분해 주면 그 자체가 가입 여부 조회 API 가 된다.
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_credentials", "メールアドレスまたはパスワードが正しくありません"))

    @ExceptionHandler(InvalidGeminiKeyException::class)
    fun handleInvalidGeminiKey(e: InvalidGeminiKeyException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("invalid_key", "キーが拒否されました。値をもう一度確認してください。"))

    /**
     * 입력 검증 실패. 어느 필드가 왜 걸렸는지까지 돌려준다.
     * 프레임워크 기본 응답을 그대로 내보내면 timestamp·path 같은 내부 형식이
     * 그대로 API 스키마가 되고, 클라이언트는 우리 오류와 다른 모양을 하나 더
     * 다뤄야 한다.
     */
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

    // ── 이미지 업로드 ──────────────────────────────────────────────

    @ExceptionHandler(UnsupportedImageTypeException::class)
    fun handleUnsupportedImage(e: UnsupportedImageTypeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorResponse("unsupported_image_type", "jpeg/png/webp の画像だけアップロードできます"))

    /**
     * 크기로 거절하는 경로는 없앴다. 서블릿 상한도 -1(무제한)이고 애플리케이션도
     * 재지 않는다 — 큰 사진은 거절할 것이 아니라 받아서 줄이면 되는 것이었다.
     *
     * 그래도 이 핸들러는 남긴다. 상한을 다시 걸 사람이 있을 수 있고, 그때
     * 컨테이너가 끊은 요청이 여기로 오지 않으면 사용자는 JSON 대신 톰캣의
     * 기본 오류 페이지를 보게 된다. 안내 문구에 숫자를 박지 않은 것도 같은
     * 이유다 — 어디서 끊겼든 사용자가 할 수 있는 일은 같다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ErrorResponse("image_too_large", "この写真は読み込めませんでした。別の写真でお試しください。"),
        )

    // ── AI ────────────────────────────────────────────────────────

    /**
     * 503. AI 를 못 쓰는 것은 서버가 고장 난 게 아니라 "지금 그 기능만 없는" 상태다.
     * 옷장 CRUD 는 그대로 200 을 준다. 원본 Django 도 키가 없다고 앱 전체를 죽이지는
     * 않았는데, 그 판단은 좋았으므로 그대로 가져왔다.
     */
    @ExceptionHandler(AiUnavailableException::class)
    fun handleAiUnavailable(e: AiUnavailableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("ai_unavailable", "AI機能は今使えません"))

    /** 502. 우리 잘못도 클라이언트 잘못도 아니고 상류가 실패했다는 뜻이다. */
    @ExceptionHandler(AiCallFailedException::class)
    fun handleAiFailed(e: AiCallFailedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("ai_failed", "AIの呼び出しに失敗しました。少し待ってからもう一度お試しください"))

    /**
     * 502. 응답은 왔지만 서버 검증에서 걸렀다(예: 존재하지 않는 clothesId).
     * 400 으로 내리지 않는 이유는 클라이언트가 보낸 것이 아무것도 없기 때문이다 —
     * 잘못은 전적으로 상류에 있고 클라이언트가 할 수 있는 건 재시도뿐이다.
     */
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

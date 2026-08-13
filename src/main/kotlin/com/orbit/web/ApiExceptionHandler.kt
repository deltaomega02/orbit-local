package com.orbit.web

import com.orbit.ai.AiCallFailedException
import com.orbit.ai.AiInvalidResponseException
import com.orbit.ai.AiUnavailableException
import com.orbit.media.ImagePixelsTooLargeException
import com.orbit.media.ImageTooLargeException
import com.orbit.media.MediaProperties
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
class ApiExceptionHandler(private val mediaProperties: MediaProperties) {

    /** 안내에 쓸 상한. 바이트가 아니라 사람이 자기 사진과 비교할 수 있는 단위로 말한다. */
    private val maxFileSizeMb: Long get() = mediaProperties.maxFileSize.toBytes() / BYTES_PER_MB

    private companion object {
        const val BYTES_PER_MB = 1024 * 1024
    }

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
     * 크기 초과는 세 경로로 들어온다. 애플리케이션 상한([ImageTooLargeException]),
     * 픽셀 수 상한([ImagePixelsTooLargeException]), 서블릿 컨테이너 상한
     * (MaxUploadSizeExceededException). 컨테이너 쪽은 요청을 다 읽기 전에 끊어주므로
     * 실제 DoS 방어는 그쪽이 하고, 나머지 둘이 그 뒤를 받친다.
     * 응답 모양은 같아야 하므로 셋을 같은 error 코드로 모은다.
     *
     * **문구가 바뀐 이유.** 예전에는 `画像の上限は8388608バイトです` 였다. 바이트 수는
     * 사람이 자기 사진과 비교할 수 있는 단위가 아니고, 무엇보다 그 시절에는 사용자가
     * 이 문장을 **자주** 봤다. 지금은 서버가 저장 전에 사진을 줄이므로
     * ([com.orbit.media.ImageNormalizer]) 여기까지 오는 것은 사실상 사진이 아닌 파일이다.
     * 그래서 문구도 "당신이 뭘 잘못했다"가 아니라 "이건 사진이 아닌 것 같다"로 쓴다.
     */
    @ExceptionHandler(ImageTooLargeException::class)
    fun handleImageTooLarge(e: ImageTooLargeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse("image_too_large", tooLargeMessage(e.maxBytes / BYTES_PER_MB)))

    private fun tooLargeMessage(limitMb: Long) =
        "画像は${limitMb}MBまでです。写真ではないファイルかもしれません。もう一度確認してください。"

    /**
     * 픽셀 수가 너무 많다. 바이트 상한과 다른 축이라 문구도 다르다 — "40MB 이하인데
     * 왜 크다는 거냐"로 읽히면 사용자는 손쓸 방법이 없다.
     */
    @ExceptionHandler(ImagePixelsTooLargeException::class)
    fun handleImagePixelsTooLarge(e: ImagePixelsTooLargeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ErrorResponse(
                "image_too_large",
                "画像の解像度が大きすぎます。約${e.maxPixels / 1_000_000}メガピクセルまで対応しています。",
            ),
        )

    /**
     * 컨테이너가 먼저 끊은 경우. **실제 사용자가 보는 것은 거의 항상 이쪽이다.**
     *
     * 두 상한이 같은 값(40MB)이라, 넘치는 요청은 애플리케이션까지 오기 전에 Tomcat 이
     * 끊는다. 그런데 예전에는 이쪽 문구만 "アップロードできるサイズを超えています" 로
     * 상한을 말하지 않았다 — 정작 친절하게 써 둔 문장은 도달할 수 없는 코드였고,
     * 사용자는 몇 MB 까지 되는지 끝내 알 수 없었다(실제로 서버를 띄워 확인하고 찾았다).
     * 어느 층이 끊었는지는 사용자의 관심사가 아니므로 같은 문장을 준다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse("image_too_large", tooLargeMessage(maxFileSizeMb)))

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

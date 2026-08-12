package com.orbit.web

import com.orbit.security.InvalidTokenException
import com.orbit.service.ClothesInUseException
import com.orbit.service.ClothesNotFoundException
import com.orbit.service.DuplicateCoordinationException
import com.orbit.service.EmailAlreadyUsedException
import com.orbit.service.InvalidCredentialsException
import com.orbit.service.UnknownClothesException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(e: InvalidTokenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("invalid_token", "유효하지 않은 토큰입니다"))
}

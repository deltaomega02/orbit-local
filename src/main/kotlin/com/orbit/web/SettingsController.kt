package com.orbit.web

import com.orbit.ai.gemini.GeminiKeyStore
import com.orbit.ai.gemini.GeminiKeyVerifier
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** XSS 시 키가 유출되지 않도록 키 원문은 어떤 응답에도 담지 않는다. */
data class GeminiKeyStatus(
    val configured: Boolean,
    val masked: String?,
)

data class GeminiKeyRequest(
    @field:NotBlank(message = "キーを入力してください")
    val key: String = "",
)

/** Gemini 가 키를 거절함. 400 `invalid_key`. */
class InvalidGeminiKeyException : RuntimeException("Gemini API 키가 거절되었습니다")

/** 키를 바꾸거나 지울 수 있는 엔드포인트라 인증이 필요하다(`/api` 하위 기본 설정). */
@RestController
@RequestMapping("/api/settings/gemini-key")
class SettingsController(
    private val keyStore: GeminiKeyStore,
    private val verifier: GeminiKeyVerifier,
) {

    @GetMapping
    fun status(): GeminiKeyStatus = current()

    /** 저장 전에 키를 검증한다. 오프라인 등으로 검증하지 못한 경우는 저장한다. */
    @PutMapping
    fun save(@Validated @RequestBody request: GeminiKeyRequest): GeminiKeyStatus {
        val key = request.key.trim()
        require(key.isNotBlank()) { "キーを入力してください" }

        if (verifier.verify(key) == GeminiKeyVerifier.Result.INVALID) {
            throw InvalidGeminiKeyException()
        }

        keyStore.save(key)
        return current()
    }

    /** 지울 키가 없어도 204. */
    @DeleteMapping
    fun delete(): ResponseEntity<Void> {
        keyStore.clear()
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    private fun current() = GeminiKeyStatus(
        configured = keyStore.isConfigured,
        masked = keyStore.masked(),
    )
}

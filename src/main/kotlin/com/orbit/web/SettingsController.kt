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

/**
 * 응답에 담기는 것은 **설정 여부와 마스킹된 조각뿐이다.** 키 원문을 돌려주는
 * 엔드포인트는 만들지 않는다 — 한 번 저장한 값을 화면에 다시 띄워줄 이유가 없고,
 * 그 편의 하나 때문에 XSS 한 방이 곧 키 유출이 된다.
 */
data class GeminiKeyStatus(
    val configured: Boolean,
    val masked: String?,
)

data class GeminiKeyRequest(
    @field:NotBlank(message = "키를 입력해 주세요")
    val key: String = "",
)

/** 키가 상대편에게 거절당했다. 400 `invalid_key` 로 나간다. */
class InvalidGeminiKeyException : RuntimeException("Gemini API 키가 거절되었습니다")

/**
 * Gemini API 키 설정.
 *
 * 인증 뒤에 둔다. 로컬 앱이라 사실상 본인뿐이지만, 이 엔드포인트는 "키를 심을 수
 * 있는 자리"라서 열어두면 같은 네트워크의 아무나 자기 키를 밀어 넣거나 남의 키를
 * 지울 수 있다. `/api` 하위는 기본이 인증 필요이므로 별도 설정 없이 닫혀 있다.
 */
@RestController
@RequestMapping("/api/settings/gemini-key")
class SettingsController(
    private val keyStore: GeminiKeyStore,
    private val verifier: GeminiKeyVerifier,
) {

    @GetMapping
    fun status(): GeminiKeyStatus = current()

    /**
     * 저장 전에 한 번 확인한다. 확인을 **못 한** 경우(오프라인 등)는 저장을 막지
     * 않는다 — 자세한 판단은 [GeminiKeyVerifier] 주석에 적었다.
     */
    @PutMapping
    fun save(@Validated @RequestBody request: GeminiKeyRequest): GeminiKeyStatus {
        val key = request.key.trim()
        require(key.isNotBlank()) { "키를 입력해 주세요" }

        if (verifier.verify(key) == GeminiKeyVerifier.Result.INVALID) {
            throw InvalidGeminiKeyException()
        }

        keyStore.save(key)
        return current()
    }

    /**
     * 204. 지울 것이 없어도 204 다 — "삭제되어 있다"는 상태는 같고, 없다고 404 를
     * 주면 클라이언트가 지우기 전에 조회를 한 번 더 해야 한다.
     */
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

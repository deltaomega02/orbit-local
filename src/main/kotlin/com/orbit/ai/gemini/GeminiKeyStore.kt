package com.orbit.ai.gemini

import com.orbit.runtime.OrbitPaths
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Gemini API 키를 사용자 데이터 폴더의 `gemini.key` 에 평문으로 두고, 파일 권한은 소유자로 제한한다.
 * 키는 계정별이 아니라 기기(앱) 전역이다. 근거는 README, 동작은 `GeminiKeyScopeTest` 참고.
 * 빈 생성 시에는 파일을 읽지 않는다(지연 읽기).
 */
@Component
class GeminiKeyStore(
    /** 개발용 환경변수 `GEMINI_API_KEY`. 저장된 키가 있으면 그쪽이 우선한다. */
    @Value("\${orbit.gemini.api-key:}") private val environmentKey: String = "",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매 호출마다 디스크를 읽지 않도록 캐시한다. 저장/삭제 시에만 무효화된다. */
    @Volatile
    private var cached: String? = null

    /** 설정된 키. 없으면 빈 문자열. */
    fun current(): String {
        cached?.let { return it }
        val resolved = readStoredKey() ?: environmentKey.trim()
        cached = resolved
        return resolved
    }

    val isConfigured: Boolean get() = current().isNotBlank()

    /** 응답에 실어도 되는 형태. 앞뒤 네 글자만 남긴다. */
    fun masked(): String? = mask(current())

    fun save(key: String) {
        val normalized = key.trim()
        require(normalized.isNotBlank()) { "キーが空です" }

        val file = OrbitPaths.geminiKeyFile
        Files.createDirectories(file.parent)
        Files.write(
            file,
            normalized.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        OrbitPaths.restrictToOwner(file)
        cached = normalized
        // 키 값은 로그에 남기지 않는다
        log.info("Gemini API 키를 저장했다")
    }

    fun clear() {
        runCatching { Files.deleteIfExists(OrbitPaths.geminiKeyFile) }
        cached = null
        log.info("Gemini API 키를 삭제했다")
    }

    private fun readStoredKey(): String? = runCatching {
        val file = OrbitPaths.geminiKeyFile
        if (!Files.isRegularFile(file)) return@runCatching null
        Files.readString(file, StandardCharsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        /** `AIzaSyA…3f2a` 처럼 앞뒤만 남긴다. 12자 미만은 전부 가린다. */
        fun mask(key: String): String? {
            val trimmed = key.trim()
            if (trimmed.isBlank()) return null
            if (trimmed.length < 12) return "…"
            return "${trimmed.take(4)}…${trimmed.takeLast(4)}"
        }
    }
}

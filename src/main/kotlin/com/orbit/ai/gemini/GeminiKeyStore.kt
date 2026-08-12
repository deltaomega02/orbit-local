package com.orbit.ai.gemini

import com.orbit.runtime.OrbitPaths
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Gemini API 키를 보관한다.
 *
 * **키를 얻는 경로는 하나뿐이다 — 사용자가 앱 설정 화면에서 넣는다.** 빌드 타임
 * 주입이나 실행 파일 옆 설정 파일 같은 경로를 함께 두면 "왜 내가 넣은 키가 안
 * 먹지"를 디버깅할 자리가 그만큼 늘어난다. 받는 사람이 한 명인 앱에서 경로가
 * 셋인 것은 비용이지 유연성이 아니다.
 *
 * 값은 사용자 데이터 폴더의 `gemini.key` 에 평문으로 둔다. 로컬 단일 사용자 앱에서
 * 이 파일을 읽을 수 있는 상대는 이미 프로세스 메모리도 읽을 수 있는 상대라,
 * 앱 안에서 하는 암호화는 열쇠를 자물쇠 옆에 두는 것 이상이 되기 어렵다. 대신
 * 파일 권한을 소유자로 조이고([OrbitPaths.restrictToOwner]) 사용자 프로필 밖으로
 * 나가지 않게 한다.
 *
 * 읽기는 **지연**이다. 빈이 만들어지는 것만으로는 아무 파일도 건드리지 않는다 —
 * 테스트 컨텍스트에도 이 빈은 존재하지만 사용자 폴더를 만들지 않는다.
 */
@Component
class GeminiKeyStore(
    /**
     * 개발 편의를 위한 최후 순위. 환경변수 `GEMINI_API_KEY` 가 여기로 들어온다.
     * 저장된 키가 있으면 그쪽이 이긴다 — 사용자가 화면에서 넣은 값이 눈에 보이지
     * 않는 환경변수에 밀리면 그게 더 이상하다.
     */
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
        require(normalized.isNotBlank()) { "키가 비어 있습니다" }

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
        // 값이 아니라 "바뀌었다"만 남긴다. 키가 로그에 새면 파일 권한은 의미가 없다.
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
        /**
         * `AIzaSyA…3f2a` 처럼 앞뒤만 남긴다. 짧은 값은 아예 가린다 — 8자짜리 키에서
         * 앞뒤 4자를 보여주면 그건 마스킹이 아니라 그냥 키다.
         */
        fun mask(key: String): String? {
            val trimmed = key.trim()
            if (trimmed.isBlank()) return null
            if (trimmed.length < 12) return "…"
            return "${trimmed.take(4)}…${trimmed.takeLast(4)}"
        }
    }
}

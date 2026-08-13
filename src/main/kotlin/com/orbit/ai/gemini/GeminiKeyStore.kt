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
 *
 * ## 키의 범위: 계정별이 아니라 **앱 전역**이다 (의도된 동작)
 *
 * 이 저장소에는 사용자(계정) 개념이 들어올 자리가 없다. 이 앱에서 새 계정을 만들면
 * 기존 계정이 넣어 둔 키를 그대로 쓰게 되고, 한 계정에서 키를 지우면 모든 계정에서
 * 사라진다. 감사에서 지적된 그대로이고, **그렇게 두기로 한 결정이다.** 근거는 셋이다.
 *
 *  1. **키가 묶이는 대상이 앱 계정이 아니라 OS 계정이다.** 값은 사용자 프로필 안
 *     (`~/Library/Application Support/Orbit`, `%LOCALAPPDATA%\Orbit`)의 파일에 600 으로
 *     들어간다. 이 파일에 닿을 수 있는 사람은 이미 그 OS 계정으로 로그인한 사람이고,
 *     그 사람은 앱 계정을 새로 만들어 로그인하는 것도 언제든 할 수 있다. 앱 계정으로
 *     칸을 나눠도 **경계를 지켜 주는 것이 아무것도 없다** — 보안이 아니라 모양이 된다.
 *  2. **계정별로 두면 키를 DB 로 옮겨야 한다.** 사용자 수만큼의 파일을 관리하거나
 *     `users` 테이블에 컬럼을 하나 붙이는 선택인데, DB 파일은 통째로 백업·복사되는
 *     대상이라 지금의 "파일 하나, 소유자만 읽기"보다 오히려 새는 면이 넓어진다.
 *     보관 위치를 나쁘게 바꿔서 얻는 것이 "혼자 쓰는 앱에서의 칸막이"뿐이다.
 *  3. **이 앱의 계정은 보안 경계가 아니라 옷장의 소유자다.** 초대도 공유도 없고
 *     둘째 계정은 사실상 "다시 시작하기"에 가깝다. 그때마다 키를 다시 발급받아
 *     붙여넣게 하는 것은 같은 사람에게 같은 일을 두 번 시키는 것이다.
 *
 * 대가는 분명하다. **이 기기를 남과 함께 쓰면 그 사람도 내 키로 AI 를 호출할 수 있고,
 * 요금은 나에게 청구된다.** 그러므로 이 사실은 숨기지 않고 화면 문구로 드러나야 한다 —
 * "이 기기의 Orbit에만 저장돼요"처럼 계정별로 읽히는 문구는 사실과 다르다.
 * "이 기기의 Orbit **전체**에 적용돼요(계정을 새로 만들어도 같은 키를 씁니다)" 쪽이
 * 맞다. 판단의 전체 근거는 README 의 "Gemini 키는 계정이 아니라 기기에 묶는다"에 있고,
 * 계정이 갈려도 같은 상태가 보인다는 사실은 `GeminiKeyScopeTest` 가 고정한다.
 *
 * 이 결정이 뒤집히는 조건도 적어 둔다. **한 기기를 여러 사람이 각자의 계정으로 쓰는
 * 것이 실제 사용 방식이 되면** 위 1번과 3번의 전제가 무너지므로 그때는 계정별로
 * 옮겨야 한다(그 시점에는 이미 저장된 키를 첫 사용자에게 귀속시키는 마이그레이션이
 * 필요하다). 지금은 그 사용 방식이 없다.
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

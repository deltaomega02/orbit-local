package com.orbit.runtime

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64

/**
 * 설치형 앱으로서 필요한 기본값을 환경에 채워 넣는다.
 *
 * 왜 `application.yml` 이 아니라 코드인가. 데이터 경로는 OS 마다 다르고 JWT
 * 시크릿은 첫 실행에 **만들어야** 하는 값이다. 둘 다 정적인 설정 파일로는 표현할
 * 수 없다.
 *
 * 두 가지를 의도적으로 지켰다.
 *
 *  1. **[addLast]** — 여기서 넣는 값은 가장 낮은 우선순위다. `application.yml`,
 *     환경변수, 커맨드라인이 전부 이걸 이긴다. 특히 테스트의
 *     `src/test/resources/application.yml` 이 그대로 이겨서, 테스트는 인메모리 DB 와
 *     `build/test-media` 를 계속 쓴다.
 *  2. **부작용을 조건부로** — JWT 키 파일 생성은 시크릿이 어디에도 설정되어 있지
 *     않을 때만 한다. 테스트는 자기 시크릿을 갖고 있으므로 사용자 폴더에 파일이
 *     만들어질 일이 없다. 로그 파일 경로도 데스크톱 모드에서만 넣는다.
 *
 * [Ordered.LOWEST_PRECEDENCE] 로 두어 설정 파일이 모두 로드된 **뒤에** 실행되게
 * 한다. 그래야 "이미 설정돼 있는가"를 물어볼 수 있다.
 */
class OrbitEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val defaults = linkedMapOf<String, Any>(
            "orbit.home" to OrbitPaths.dataDir.toString(),
            "orbit.media.dir" to OrbitPaths.mediaDir.toString(),
            // H2 를 파일 모드로. 기본값이던 `jdbc:h2:mem:` 은 껐다 켜면 옷장이 통째로
            // 사라지는 설정이라, 설치해서 쓰는 앱에서는 그 자체로 버그였다.
            "spring.datasource.url" to OrbitPaths.h2Url(),
        )

        if (DesktopRuntime.enabled) {
            // 콘솔이 없는 GUI 앱이라 표준 출력은 아무 데도 남지 않는다. 문제가 생겼을 때
            // 볼 것이 있어야 하므로 파일로 돌린다.
            defaults["logging.file.name"] = OrbitPaths.logDir.resolve("orbit.log").toString()
            defaults["logging.logback.rollingpolicy.max-file-size"] = "5MB"
            defaults["logging.logback.rollingpolicy.max-history"] = "7"
            defaults["logging.logback.rollingpolicy.total-size-cap"] = "50MB"
        }

        if (environment.getProperty("orbit.jwt.secret").isNullOrBlank()) {
            defaults["orbit.jwt.secret"] = readOrCreateJwtSecret()
        }

        environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, defaults))
    }

    /**
     * JWT 시크릿을 첫 실행에 만들어 두고 그 뒤로는 재사용한다.
     *
     * 사용자에게 "32자 이상의 문자열을 만들어 환경변수에 넣으세요"라고 요구할 수는
     * 없다. 그렇다고 앱에 상수로 박으면 모든 설치본이 같은 키를 쓰게 되고, 그건
     * 키가 없는 것과 크게 다르지 않다. 그래서 기기마다 다른 값을 한 번 만들어
     * 사용자 폴더에 둔다.
     *
     * 값이 바뀌면 발급된 토큰이 전부 무효가 되므로(= 다시 로그인) 파일을 지우는
     * 것 외에는 절대 재생성하지 않는다.
     */
    private fun readOrCreateJwtSecret(): String {
        val file = OrbitPaths.jwtSecretFile
        runCatching {
            if (Files.isRegularFile(file)) {
                val existing = Files.readString(file).trim()
                if (existing.toByteArray(Charsets.UTF_8).size >= 32) return existing
            }
        }

        val generated = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })

        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                generated,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            OrbitPaths.restrictToOwner(file)
        }.onFailure {
            // 파일에 못 남기면 이번 실행에서만 쓰는 값이 된다. 다음 실행에 다시
            // 로그인해야 할 뿐, 앱이 뜨지 못하는 것보다는 낫다.
        }
        return generated
    }

    private companion object {
        const val SOURCE_NAME = "orbitRuntimeDefaults"
    }
}

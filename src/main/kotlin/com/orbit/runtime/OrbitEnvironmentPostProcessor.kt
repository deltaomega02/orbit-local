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
 * OS 별 데이터 경로와 기기별 JWT 시크릿처럼 정적 설정으로 표현할 수 없는 기본값을 넣는다.
 * addLast 로 가장 낮은 우선순위라 yml·환경변수·테스트 설정이 모두 이긴다.
 * 설정 파일이 로드된 뒤 기존 값을 확인할 수 있도록 LOWEST_PRECEDENCE 로 실행한다.
 */
class OrbitEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val defaults = linkedMapOf<String, Any>(
            "orbit.home" to OrbitPaths.dataDir.toString(),
            "orbit.media.dir" to OrbitPaths.mediaDir.toString(),
            // 재시작해도 데이터가 남도록 파일 모드
            "spring.datasource.url" to OrbitPaths.h2Url(),
        )

        if (DesktopRuntime.enabled) {
            // 콘솔이 없으므로 로그를 파일로 남긴다
            defaults["logging.file.name"] = OrbitPaths.logDir.resolve("orbit.log").toString()
            defaults["logging.logback.rollingpolicy.max-file-size"] = "5MB"
            defaults["logging.logback.rollingpolicy.max-history"] = "7"
            defaults["logging.logback.rollingpolicy.total-size-cap"] = "50MB"
        }

        if (environment.getProperty("orbit.jwt.secret").isNullOrBlank()) {
            defaults["orbit.jwt.secret"] = readOrCreateJwtSecret()
        }

        environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, defaults))

        installDesktopJwtSecret(environment)
    }

    /**
     * yml 이 개발용 기본 시크릿을 항상 채우므로, 데스크톱 모드에서는 addFirst 로 기기별 시크릿을 덮어쓴다.
     * ORBIT_JWT_SECRET 환경변수를 직접 준 경우는 건드리지 않는다.
     */
    private fun installDesktopJwtSecret(environment: ConfigurableEnvironment) {
        if (!DesktopRuntime.enabled) return
        if (!System.getenv("ORBIT_JWT_SECRET").isNullOrBlank()) return

        environment.propertySources.addFirst(
            MapPropertySource(DESKTOP_SOURCE_NAME, mapOf("orbit.jwt.secret" to readOrCreateJwtSecret())),
        )
    }

    /** 첫 실행에 기기별 시크릿을 만들어 사용자 폴더에 두고 재사용한다. 바뀌면 기존 토큰이 모두 무효가 된다. */
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
            // 저장에 실패해도 이번 실행에서만 쓰고 기동은 계속한다
        }
        return generated
    }

    private companion object {
        const val SOURCE_NAME = "orbitRuntimeDefaults"
        const val DESKTOP_SOURCE_NAME = "orbitDesktopSecrets"
    }
}

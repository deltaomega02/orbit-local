package com.orbit.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 앱 삭제·권한 문제와 무관하도록 OS 표준 사용자 데이터 위치를 쓴다.
 * Windows `%LOCALAPPDATA%\Orbit`, macOS `~/Library/Application Support/Orbit`, 그 외 `~/.local/share/orbit`.
 */
object OrbitPaths {

    private const val APP_NAME = "Orbit"

    enum class Os { WINDOWS, MACOS, OTHER }

    val os: Os = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("win") -> Os.WINDOWS
            it.startsWith("mac") -> Os.MACOS
            else -> Os.OTHER
        }
    }

    private val userHome: Path get() = Paths.get(System.getProperty("user.home", "."))

    /** 미디어·DB·시크릿의 루트. `ORBIT_HOME` 으로 바꿀 수 있다. */
    val dataDir: Path by lazy {
        val override = System.getenv("ORBIT_HOME")?.takeIf { it.isNotBlank() }
        if (override != null) return@lazy Paths.get(override).toAbsolutePath().normalize()

        when (os) {
            Os.WINDOWS -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let { Paths.get(it) } ?: userHome.resolve("AppData").resolve("Local"))
                    .resolve(APP_NAME)
            }
            Os.MACOS -> userHome.resolve("Library").resolve("Application Support").resolve(APP_NAME)
            Os.OTHER -> userHome.resolve(".local").resolve("share").resolve(APP_NAME.lowercase())
        }.toAbsolutePath().normalize()
    }

    /** macOS 는 관례대로 `~/Library/Logs`, 그 외는 데이터 루트 아래 `logs`. */
    val logDir: Path by lazy {
        when (os) {
            Os.MACOS -> userHome.resolve("Library").resolve("Logs").resolve(APP_NAME).toAbsolutePath().normalize()
            else -> dataDir.resolve("logs")
        }
    }

    val mediaDir: Path get() = dataDir.resolve("media")

    /** 실제 파일은 `orbit.mv.db`. */
    val databaseBase: Path get() = dataDir.resolve("db").resolve("orbit")

    val jwtSecretFile: Path get() = dataDir.resolve("jwt.key")

    val geminiKeyFile: Path get() = dataDir.resolve("gemini.key")

    /** 프로세스가 죽으면 OS 가 잠금을 푼다. */
    val lockFile: Path get() = dataDir.resolve("orbit.lock")

    val portFile: Path get() = dataDir.resolve("orbit.port")

    fun h2Url(): String {
        // 역슬래시가 URL 에서 이스케이프로 오해되지 않도록 슬래시로 바꾼다
        val base = databaseBase.toString().replace('\\', '/')
        // DB_CLOSE_ON_EXIT=FALSE: H2 셧다운 훅이 먼저 닫지 않고 스프링이 종료 순서를 잡게 한다.
        // WRITE_DELAY=0: 강제 종료 시 마지막 1초의 쓰기가 유실되지 않도록 즉시 기록한다.
        return "jdbc:h2:file:$base;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    }

    /** POSIX 에서는 600 으로 설정한다. Windows 는 건너뛰며 사용자 프로필 폴더 권한에 의존한다. */
    fun restrictToOwner(file: Path) {
        runCatching {
            val view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView::class.java)
                ?: return
            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}

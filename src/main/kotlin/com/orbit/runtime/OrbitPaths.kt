package com.orbit.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 사용자 데이터가 놓일 자리.
 *
 * 개발할 때처럼 프로젝트 폴더 안(`./data`)에 쌓으면 설치형 앱에서는 곧바로 사고가 된다.
 * 앱이 놓인 자리는 사용자가 언제든 통째로 지우거나 옮기는 곳이고(Program Files 처럼
 * 쓰기 권한이 없을 수도 있다), 그 안에 사진과 DB 가 있으면 앱을 지우는 순간 사진도
 * 같이 사라진다. 그래서 OS 가 "사용자 데이터는 여기"라고 정해 둔 자리를 쓴다.
 *
 *  - Windows : `%LOCALAPPDATA%\Orbit`
 *  - macOS   : `~/Library/Application Support/Orbit`
 *  - 그 외    : `~/.local/share/orbit`
 *
 * 배포 대상은 Windows 지만 개발은 macOS 에서 한다. 그래서 경로를 하드코딩하지 않고
 * OS 로 분기한다 — 한쪽에서만 도는 코드는 다른 쪽에서 반드시 썩는다.
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

    /**
     * 데이터 루트. 미디어·DB·시크릿이 전부 이 아래로 들어간다.
     *
     * `ORBIT_HOME` 으로 덮어쓸 수 있게 열어 둔 이유는 두 가지다. 하나는 이동식
     * 디스크에 통째로 올려 쓰고 싶을 때, 다른 하나는 문제가 생겼을 때 깨끗한
     * 상태로 한 번 띄워보기 위해서다.
     */
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

    /**
     * 로그 자리. macOS 만 관례가 따로 있어서(`~/Library/Logs`) 분기한다.
     * Windows 는 데이터와 같은 뿌리 아래 `logs\` 로 둔다 — 사용자가 문제를 보고할 때
     * "폴더 하나만 통째로 보내주세요"가 되는 편이 낫다.
     */
    val logDir: Path by lazy {
        when (os) {
            Os.MACOS -> userHome.resolve("Library").resolve("Logs").resolve(APP_NAME).toAbsolutePath().normalize()
            else -> dataDir.resolve("logs")
        }
    }

    val mediaDir: Path get() = dataDir.resolve("media")

    /** H2 파일 DB 의 베이스 경로. 실제로는 `orbit.mv.db` 가 만들어진다. */
    val databaseBase: Path get() = dataDir.resolve("db").resolve("orbit")

    val jwtSecretFile: Path get() = dataDir.resolve("jwt.key")

    val geminiKeyFile: Path get() = dataDir.resolve("gemini.key")

    /** 중복 실행 방지용 잠금 파일. 열린 채로 유지되고 프로세스가 죽으면 OS 가 푼다. */
    val lockFile: Path get() = dataDir.resolve("orbit.lock")

    /** 이미 떠 있는 인스턴스가 몇 번 포트를 쓰는지 적어 두는 자리. */
    val portFile: Path get() = dataDir.resolve("orbit.port")

    /**
     * H2 JDBC URL.
     *
     * Windows 경로의 역슬래시를 슬래시로 바꾼다. H2 는 양쪽 다 받지만 URL 문자열
     * 안에서 역슬래시는 이스케이프로 오해되기 쉬운 자리라, 헷갈릴 여지를 없앤다.
     */
    fun h2Url(): String {
        val base = databaseBase.toString().replace('\\', '/')
        // DB_CLOSE_ON_EXIT=FALSE — 종료 순서는 스프링 컨텍스트가 잡는다. H2 가 먼저
        // 셧다운 훅으로 닫아버리면 마지막 플러시가 "이미 닫힌 DB" 오류로 날아간다.
        // WRITE_DELAY=0 — 커밋을 모아뒀다 내리지 않고 즉시 디스크에 쓴다.
        // H2 기본값(1초)이면 앱이 강제 종료되거나 전원이 끊길 때 마지막 1초의
        // 쓰기가 사라진다. 실제로 그렇게 옷 4벌이 통째로 날아가는 것을 확인했다.
        // 대가는 쓰기마다 디스크 동기화가 도는 것인데, 이 앱의 쓰기는 하루 몇 건이라
        // 체감되지 않는다. 옷 한 벌을 잃는 쪽이 훨씬 비싸다.
        return "jdbc:h2:file:$base;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    }

    /**
     * 소유자만 읽을 수 있게 조인다.
     *
     * POSIX(macOS/리눅스)에서는 600 이 그대로 되고, Windows 에는 이 개념이 없어서
     * 조용히 넘어간다. Windows 에서 `%LOCALAPPDATA%` 는 이미 사용자 프로필 안이라
     * 다른 계정이 기본적으로 읽지 못한다 — 완벽하지는 않지만 이게 그 플랫폼의 선이다.
     */
    fun restrictToOwner(file: Path) {
        runCatching {
            val view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView::class.java)
                ?: return
            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}

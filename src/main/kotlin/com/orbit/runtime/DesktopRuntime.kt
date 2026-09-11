package com.orbit.runtime

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * 데스크톱 모드 여부는 시스템 프로퍼티 `orbit.desktop` 으로만 판단한다.
 * 테스트는 main 을 거치지 않으므로 항상 꺼져 있다.
 */
object DesktopRuntime {
    const val PROPERTY = "orbit.desktop"

    val enabled: Boolean get() = System.getProperty(PROPERTY) == "true"

    /** `-Dorbit.desktop=false` 로 명시했으면 켜지 않는다. */
    fun enableUnlessOverridden() {
        if (System.getProperty(PROPERTY) == null) System.setProperty(PROPERTY, "true")
    }

    /** jpackage 런처는 `jpackage.app-path` 를 항상 설정한다. */
    fun launchedFromPackagedApp(): Boolean = !System.getProperty("jpackage.app-path").isNullOrBlank()

    /** 기본 8080. 막혀 있으면 [FreePort] 가 다른 포트를 고른다. */
    fun preferredPort(): Int =
        setting("orbit.port", "ORBIT_PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080

    /** 기본 true. 무인 기동 확인용으로 끌 수 있다. */
    val opensBrowser: Boolean
        get() = !"false".equals(setting("orbit.open-browser", "ORBIT_OPEN_BROWSER"), ignoreCase = true)

    private fun setting(property: String, env: String): String? =
        (System.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
}

/** 이미 떠 있는 인스턴스가 있을 때 두 번째 실행의 처리. */
object SecondInstance {

    /**
     * 떠 있는 인스턴스가 다른 빌드([buildStamp] 기준)면 종료시키고 잠금을 넘겨받는다.
     *
     * @return 잠금을 넘겨받아 계속 실행해도 되면 true.
     */
    fun replaceOutdatedInstance(): Boolean {
        val stamp = SingleInstance.publishedStamp()
        if (stamp != null && stamp == SingleInstance.buildStamp()) return false

        val pid = SingleInstance.publishedPid() ?: return false
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        // PID 는 재사용되므로 남의 프로세스인지 확인한다
        if (!looksLikeOrbit(handle)) return false

        // 윈도우에서 destroy() 는 강제 종료지만 H2 WRITE_DELAY=0 이라 커밋된 데이터는 남는다
        handle.destroy()
        runCatching { handle.onExit().get(EXIT_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }

        // 프로세스 종료 후 파일 잠금이 풀리기까지 잠깐 걸린다
        repeat(LOCK_RETRIES) {
            if (SingleInstance.acquire()) return true
            Thread.sleep(LOCK_RETRY_INTERVAL_MS)
        }
        return false
    }

    private fun looksLikeOrbit(handle: ProcessHandle): Boolean =
        handle.info().command().orElse("").contains("orbit", ignoreCase = true)

    private const val EXIT_WAIT_SECONDS = 15L
    private const val LOCK_RETRIES = 20
    private const val LOCK_RETRY_INTERVAL_MS = 250L

    fun handOverToRunningInstance() {
        // 무인 실행에서 대화 상자가 떠 프로세스가 멈추지 않게 한다
        if (!DesktopRuntime.opensBrowser) return

        val port = SingleInstance.publishedPort()
        if (port != null && BrowserOpener.open("http://127.0.0.1:$port")) return

        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "Orbit はすでに起動しています。\n" +
                    "画面右下の通知領域（時計のとなり）にある Orbit のアイコンをクリックしてください。",
                "Orbit",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }
}

/**
 * 선호 포트부터 순서대로 빈 포트를 찾는다.
 * 확인 후 톰캣이 바인딩하기 전까지 틈이 있지만 로컬 앱이라 무시한다.
 */
object FreePort {
    fun pick(preferred: Int = 8080, span: Int = 40): Int {
        for (port in preferred until preferred + span) {
            if (isFree(port)) return port
        }
        // 0 이면 톰캣이 임의 포트를 잡는다
        return 0
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true }
    } catch (e: IOException) {
        false
    }
}

/** H2 파일 DB 는 한 프로세스만 열 수 있으므로 파일 잠금으로 단일 인스턴스를 보장한다. */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** 다른 인스턴스가 잠금을 잡고 있으면 false. */
    fun acquire(): Boolean {
        return try {
            Files.createDirectories(OrbitPaths.dataDir)
            val ch = FileChannel.open(
                OrbitPaths.lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val acquired = try {
                ch.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (acquired == null) {
                ch.close()
                false
            } else {
                // 프로세스가 끝날 때까지 잠금을 유지하므로 닫지 않는다
                channel = ch
                lock = acquired
                true
            }
        } catch (e: IOException) {
            // 잠금 파일을 못 만들면 중복 실행 방지보다 기동을 우선한다
            true
        }
    }

    /**
     * 포트 파일은 포트, PID, [buildStamp] 세 줄이다.
     * 한 줄(포트만)짜리 옛 형식도 첫 줄은 읽히지만 PID 가 없어 교체는 할 수 없다.
     */
    fun publishPort(port: Int) {
        runCatching {
            Files.createDirectories(OrbitPaths.dataDir)
            val body = listOf(port.toString(), ProcessHandle.current().pid().toString(), buildStamp())
                .joinToString("\n")
            Files.write(OrbitPaths.portFile, body.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun line(index: Int): String? = runCatching {
        Files.readString(OrbitPaths.portFile, StandardCharsets.UTF_8)
            .lineSequence().drop(index).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun publishedPort(): Int? = line(0)?.toIntOrNull()

    fun publishedPid(): Long? = line(1)?.toLongOrNull()

    fun publishedStamp(): String? = line(2)

    /**
     * 실행 파일의 크기와 수정 시각. 버전 문자열은 빌드마다 바뀐다는 보장이 없어 쓰지 않는다.
     * 구하지 못하면 "unknown" 이며 다른 빌드로 간주해 새 실행이 이긴다.
     */
    fun buildStamp(): String {
        val path = System.getProperty("jpackage.app-path")
            ?: System.getProperty("java.class.path")?.split(java.io.File.pathSeparator)?.firstOrNull()
        val file = path?.let { java.io.File(it) }
        return if (file != null && file.isFile) "${file.length()}-${file.lastModified()}" else "unknown"
    }

    fun releasePort() {
        runCatching { Files.deleteIfExists(OrbitPaths.portFile) }
    }
}

/** AWT Desktop 이 안 되면 OS 명령으로 연다. */
object BrowserOpener {
    fun open(url: String): Boolean {
        if (openWithAwt(url)) return true
        return openWithShell(url)
    }

    private fun openWithAwt(url: String): Boolean = runCatching {
        if (!java.awt.Desktop.isDesktopSupported()) return false
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) return false
        desktop.browse(java.net.URI(url))
        true
    }.getOrDefault(false)

    private fun openWithShell(url: String): Boolean = runCatching {
        val command = when (OrbitPaths.os) {
            // cmd /c start 와 달리 콘솔 창이 번쩍이지 않는다
            OrbitPaths.Os.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            OrbitPaths.Os.MACOS -> listOf("open", url)
            OrbitPaths.Os.OTHER -> listOf("xdg-open", url)
        }
        ProcessBuilder(command).start()
        true
    }.getOrDefault(false)
}

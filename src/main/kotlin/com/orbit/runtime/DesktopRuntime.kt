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
 * "지금 데스크톱 앱으로 떠 있는가"의 단일 판단 지점.
 *
 * 값은 시스템 프로퍼티 하나(`orbit.desktop`)로만 정한다. jpackage 런처가
 * `-Dorbit.desktop=true` 를 넣고, [com.orbit.main] 은 **포장된 실행 파일로 떴을 때만**
 * 켠다([launchedFromPackagedApp]). `./gradlew bootRun` 은 예전과 똑같이 동작한다 —
 * 개발 중에 트레이 아이콘이 생기고 브라우저가 튀어나오면 그건 도움이 아니라 방해다.
 *
 * **테스트는 main 을 거치지 않으므로 항상 꺼진 상태다** — 덕분에 트레이 아이콘,
 * 브라우저 자동 실행, 사용자 폴더 로그 같은 부작용이 테스트에 새어 들어올 수 없다.
 */
object DesktopRuntime {
    const val PROPERTY = "orbit.desktop"

    val enabled: Boolean get() = System.getProperty(PROPERTY) == "true"

    /** 명시적으로 꺼둔 경우(`-Dorbit.desktop=false`)는 존중한다. */
    fun enableUnlessOverridden() {
        if (System.getProperty(PROPERTY) == null) System.setProperty(PROPERTY, "true")
    }

    /**
     * jpackage 가 만든 런처는 실행 파일의 위치를 `jpackage.app-path` 에 넣어 준다.
     * 이 값이 있다는 것은 곧 "사용자가 exe 를 더블클릭했다"는 뜻이다.
     */
    fun launchedFromPackagedApp(): Boolean = !System.getProperty("jpackage.app-path").isNullOrBlank()

    /**
     * 먼저 시도해 볼 포트. 기본 8080 이고, 막혀 있으면 [FreePort] 가 옆으로 옮긴다.
     *
     * 굳이 바꿀 수 있게 열어 둔 이유는 검증 때문이다. 이미 8080 에서 개발 서버가
     * 돌고 있는 기계에서 설치본을 한 번 띄워보려면 "8080 부터 훑는다"는 기본 동작이
     * 오히려 방해가 된다.
     */
    fun preferredPort(): Int =
        setting("orbit.port", "ORBIT_PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080

    /**
     * 기동 후 브라우저를 자동으로 열 것인가. 기본은 연다 — 이게 앱의 첫 화면이다.
     * 끄는 길을 남겨 둔 것은 사람이 없는 자리에서 기동만 확인하고 싶을 때를 위해서다.
     */
    val opensBrowser: Boolean
        get() = !"false".equals(setting("orbit.open-browser", "ORBIT_OPEN_BROWSER"), ignoreCase = true)

    private fun setting(property: String, env: String): String? =
        (System.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 두 번째 실행이 물러나는 방식.
 *
 * 사용자는 "앱이 안 떠서" 아이콘을 또 누른 것이다. 그 상황에서 아무 반응 없이 죽으면
 * 같은 행동을 반복하게 된다. 이미 떠 있는 인스턴스의 화면을 열어 주는 것이 사용자가
 * 원래 원했던 결과이고, 그마저 실패하면 최소한 "이미 켜져 있다"고 말은 해 준다.
 */
object SecondInstance {
    fun handOverToRunningInstance() {
        // 브라우저를 열지 않기로 한 실행이라면 안내 창도 띄우지 않는다. 사람이
        // 보고 있지 않은 자리에서 대화 상자가 뜨면 그 프로세스는 영영 안 끝난다.
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
 * 빈 포트를 고른다.
 *
 * 8080 은 흔해서 이미 누가 쓰고 있을 확률이 낮지 않고, 그때 앱이 "포트가 사용
 * 중입니다"라며 뜨지 않으면 터미널을 쓰지 않는 사용자에게는 손쓸 방법이 없다.
 * 그래서 선호 포트를 먼저 시도하고, 막혀 있으면 옆으로 옮긴다.
 *
 * 확인한 뒤 소켓을 닫고 톰캣이 다시 여는 사이에 이론적인 틈이 있지만, 개인용
 * 로컬 앱에서 그 찰나에 다른 프로세스가 같은 포트를 채갈 확률은 무시할 수 있다.
 */
object FreePort {
    fun pick(preferred: Int = 8080, span: Int = 40): Int {
        for (port in preferred until preferred + span) {
            if (isFree(port)) return port
        }
        // 전부 막혔으면 OS 에게 아무 포트나 달라고 한다. 0 을 주면 톰캣이 알아서 잡는다.
        return 0
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true }
    } catch (e: IOException) {
        false
    }
}

/**
 * 한 번에 하나만 뜨게 한다.
 *
 * H2 파일 DB 는 한 프로세스만 열 수 있어서, 두 번째 인스턴스는 어차피 DB 를 잡지
 * 못하고 죽는다. 사용자에게는 "아이콘을 두 번 눌렀더니 아무 일도 안 일어났다"로
 * 보인다. 그래서 두 번째 실행은 **실패시키는 대신 이미 떠 있는 창을 열어준다.**
 */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** 잠금을 잡으면 true. 이미 다른 인스턴스가 잡고 있으면 false. */
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
                // 프로세스가 살아 있는 동안 계속 쥐고 있어야 한다. 닫지 않는다.
                channel = ch
                lock = acquired
                true
            }
        } catch (e: IOException) {
            // 잠금 파일을 못 만드는 환경이라면 중복 실행을 막는 것보다 뜨는 게 낫다.
            true
        }
    }

    fun publishPort(port: Int) {
        runCatching {
            Files.createDirectories(OrbitPaths.dataDir)
            Files.write(OrbitPaths.portFile, port.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun publishedPort(): Int? = runCatching {
        Files.readString(OrbitPaths.portFile, StandardCharsets.UTF_8).trim().toIntOrNull()
    }.getOrNull()

    fun releasePort() {
        runCatching { Files.deleteIfExists(OrbitPaths.portFile) }
    }
}

/** 기본 브라우저로 URL 을 연다. AWT 가 안 되는 환경도 있어서 OS 명령을 백업으로 둔다. */
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
            // rundll32 은 콘솔 창을 띄우지 않는다. `cmd /c start` 는 순간적으로 검은 창이 번쩍인다.
            OrbitPaths.Os.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            OrbitPaths.Os.MACOS -> listOf("open", url)
            OrbitPaths.Os.OTHER -> listOf("xdg-open", url)
        }
        ProcessBuilder(command).start()
        true
    }.getOrDefault(false)
}

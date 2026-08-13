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

    /**
     * **새 버전이 옛 버전을 밀어낸다.**
     *
     * 이게 없을 때 실제로 이런 일이 있었다. 새 배포본을 받아 exe 를 눌렀는데, 옛
     * 인스턴스가 아직 떠 있어서 아래 [handOverToRunningInstance] 가 그쪽 화면을
     * 열어 줬다. 사용자가 본 것은 **방금 고친 것이 하나도 반영되지 않은 옛 앱**이고,
     * 새 exe 는 조용히 죽었다. 고쳤다고 말한 것이 안 고쳐져 있으니 앱을 의심하기
     * 전에 사람을 의심하게 된다.
     *
     * 그래서 같은 빌드가 아니면 떠 있는 쪽을 끝내고 자리를 넘겨받는다. 같은
     * 빌드면 굳이 다시 뜰 이유가 없으므로 예전처럼 화면만 열어 준다 — 아이콘을
     * 두 번 눌렀다고 앱이 매번 재시작하면 그것대로 이상하다.
     *
     * 빌드를 구별하는 값은 실행 파일 자체의 크기와 수정 시각이다([buildStamp]).
     * 버전 문자열은 배포마다 바뀐다는 보장이 없어서(코드의 version 은 그대로 두고
     * 패키지 버전만 올리는 일이 흔하다) 신뢰할 수 없다.
     *
     * @return 자리를 넘겨받아 계속 떠도 되면 true.
     */
    fun replaceOutdatedInstance(): Boolean {
        val stamp = SingleInstance.publishedStamp()
        // 같은 빌드가 이미 떠 있다 — 다시 뜰 이유가 없다.
        if (stamp != null && stamp == SingleInstance.buildStamp()) return false

        val pid = SingleInstance.publishedPid() ?: return false
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        // PID 는 재사용된다. 남의 프로세스를 끄지 않도록 실행 파일 이름을 확인한다.
        if (!looksLikeOrbit(handle)) return false

        /*
         * 윈도우에는 유닉스의 TERM 같은 "정리하고 끝내라"가 없어서 destroy() 도
         * 결국 TerminateProcess 다. 그래도 데이터는 남는다 — H2 를 WRITE_DELAY=0
         * 으로 열어 두었기 때문에 커밋된 것은 이미 디스크에 있다. 사진은 DB 에
         * 기록되기 전에 파일로 먼저 떨어진다.
         */
        handle.destroy()
        runCatching { handle.onExit().get(EXIT_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }

        // 프로세스가 사라져도 파일 잠금이 풀리는 데 찰나가 걸린다. 몇 번 더 두드린다.
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

    /**
     * 떠 있는 인스턴스가 자기를 알리는 파일. 세 줄이다.
     *
     *   1) 포트   — 두 번째 실행이 화면을 열어 줄 주소
     *   2) PID    — 새 빌드가 옛 빌드를 끝낼 때 쓴다
     *   3) 빌드   — 끝낼 필요가 있는지 판단하는 값 ([buildStamp])
     *
     * 예전에는 포트 한 줄이었다. 줄을 늘리면서도 첫 줄은 그대로 포트로 두었기
     * 때문에, 옛 인스턴스가 써 둔 파일도 포트만은 읽힌다(= 화면 열어 주기는 계속
     * 동작한다). 다만 PID 가 없어서 **옛 인스턴스를 끝낼 수는 없다.** 이 판의
     * 자리바꿈은 새 형식으로 쓴 인스턴스부터 적용된다.
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
     * 이 실행이 어느 빌드인지 나타내는 값.
     *
     * 실행 파일의 크기와 수정 시각을 쓴다. 다시 빌드하면 반드시 둘 중 하나는 바뀌고,
     * 같은 파일을 다시 실행하면 반드시 같다 — 여기서 필요한 성질은 그 두 가지뿐이다.
     * 버전 문자열은 쓰지 않는다. 코드의 version 을 그대로 둔 채 패키지 버전만 올리는
     * 일이 흔해서, 다른 빌드가 같은 이름을 달고 나오는 것을 막지 못한다.
     *
     * 값을 못 구하면 "unknown" 이고, 그때는 서로 다른 빌드로 친다. 판단이 안 서면
     * **새로 뜨는 쪽에 무게를 둔다** — 옛것이 살아남아 새 배포본이 무시되는 쪽이
     * 훨씬 나쁜 실패다.
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

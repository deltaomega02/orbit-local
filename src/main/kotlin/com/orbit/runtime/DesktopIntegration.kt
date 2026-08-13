package com.orbit.runtime

import org.slf4j.LoggerFactory
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * 데스크톱 앱으로 떴을 때만 붙는 껍데기.
 *
 * 서버가 준비되면 (1) 실제 포트를 기록하고 (2) 브라우저를 열고 (3) 사용자가 종료할
 * 수단을 만든다. 세 번째가 특히 중요하다 — 브라우저 탭을 닫아도 서버 프로세스는
 * 그대로 남는데, 터미널을 쓰지 않는 사용자에게는 그걸 끌 방법이 없다. 작업 관리자를
 * 열라고 안내하는 건 답이 아니다.
 *
 * 기본은 트레이(알림 영역) 아이콘이고, 트레이를 못 쓰는 환경을 위해 작은 창을
 * 백업으로 둔다. 둘 다 실패하는 경우는 사실상 없지만, 종료 수단이 아예 없는 상태로
 * 앱을 넘기는 것보다는 창 하나가 뜨는 편이 낫다.
 *
 * [ConditionalOnProperty] 로 막아 두었기 때문에 테스트 컨텍스트에는 이 빈이 아예
 * 만들어지지 않는다. 테스트가 트레이 아이콘을 띄우거나 브라우저를 여는 일은 없다.
 */
@Component
@ConditionalOnProperty(name = [DesktopRuntime.PROPERTY], havingValue = "true")
class DesktopIntegration(
    private val context: ConfigurableApplicationContext,
) : ApplicationListener<ApplicationReadyEvent> {

    private val log = LoggerFactory.getLogger(javaClass)
    private var trayIcon: TrayIcon? = null
    private var fallbackWindow: Frame? = null

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        val port = (context as? WebServerApplicationContext)?.webServer?.port ?: return
        // `localhost` 가 아니라 127.0.0.1 을 쓴다. 서버는 `server.address` 로 IPv4
        // 루프백에만 바인딩돼 있는데 `localhost` 는 기기에 따라 ::1(IPv6) 로 먼저
        // 풀린다. 브라우저가 대개 IPv4 로 다시 시도해 주긴 하지만, 앱을 켰을 때 첫
        // 화면이 뜨는 일을 그 재시도에 맡길 이유는 없다.
        val url = "http://127.0.0.1:$port"

        SingleInstance.publishPort(port)
        // 정상 종료든 강제 종료든 포트 파일이 남아 있으면 다음 실행이 헛다리를 짚는다.
        Runtime.getRuntime().addShutdownHook(Thread { SingleInstance.releasePort() })

        log.info("Orbit 준비 완료 — {}", url)

        if (!installTray(url)) {
            installFallbackWindow(url)
        }
        installMacQuitHandler()

        if (!DesktopRuntime.opensBrowser) {
            log.info("브라우저 자동 실행이 꺼져 있다. 직접 {} 로 접속한다", url)
            return
        }
        if (!BrowserOpener.open(url)) {
            log.warn("브라우저를 자동으로 열지 못했다. 직접 {} 로 접속해야 한다", url)
        }
    }

    // ── 종료 ──────────────────────────────────────────────────────

    /**
     * 스프링 컨텍스트를 먼저 닫고 프로세스를 끝낸다.
     *
     * 곧바로 [exitProcess] 를 부르면 톰캣과 H2 가 정리되기 전에 JVM 이 사라져서,
     * 최악의 경우 마지막 쓰기가 DB 파일에 반영되지 않는다. 개인 사진첩 앱에서
     * "방금 등록한 옷이 없어졌다"는 가장 나쁜 실패다.
     *
     * AWT 이벤트 스레드에서 컨텍스트를 닫으면 종료 도중 UI 가 멎으므로 별도
     * 스레드로 뺀다.
     */
    fun quit() {
        Thread({
            runCatching { trayIcon?.let { SystemTray.getSystemTray().remove(it) } }
            runCatching { fallbackWindow?.dispose() }
            val code = runCatching { SpringApplication.exit(context, ExitCodeGenerator { 0 }) }.getOrDefault(0)
            SingleInstance.releasePort()
            exitProcess(code)
        }, "orbit-shutdown").start()
    }

    private fun installMacQuitHandler() {
        if (OrbitPaths.os != OrbitPaths.Os.MACOS) return
        runCatching {
            java.awt.Desktop.getDesktop().setQuitHandler { _, response ->
                response.cancelQuit() // 우리가 직접 정리한 뒤 프로세스를 끝낸다
                quit()
            }
        }
    }

    // ── 트레이 ────────────────────────────────────────────────────

    private fun installTray(url: String): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()

        // 이 메뉴를 읽는 사람은 일본어 사용자다. 개발자에게만 보이는 로그와 달리
        // 여기 문구는 **받는 사람이 앱을 끄는 유일한 길**이라 그 사람의 말이어야 한다.
        val menu = PopupMenu()
        menu.add(MenuItem("Orbit を開く").apply { addActionListener { BrowserOpener.open(url) } })
        menu.addSeparator()
        menu.add(MenuItem("終了 (Orbit を閉じる)").apply { addActionListener { quit() } })

        val icon = TrayIcon(appIcon(tray.trayIconSize.width.coerceAtLeast(16)), "Orbit — $url", menu)
        icon.isImageAutoSize = true
        icon.addActionListener { BrowserOpener.open(url) }
        tray.add(icon)
        trayIcon = icon

        // 브라우저 탭을 닫는 것과 앱을 끄는 것이 다르다는 사실은 알려 주지 않으면
        // 알 수 없다. 첫 화면이 뜨는 순간 종료 수단의 위치를 한 번 짚어 준다 —
        // 이 안내가 없으면 사용자는 앱을 껐다고 믿고 프로세스는 계속 남는다.
        runCatching {
            icon.displayMessage(
                "Orbit を起動しました",
                "終了するときは、この Orbit アイコンを右クリックして「終了」を選んでください。\n" +
                    "ブラウザーのタブを閉じただけでは終了しません。",
                TrayIcon.MessageType.INFO,
            )
        }
        true
    }.getOrElse {
        log.warn("트레이 아이콘을 만들지 못했다: {}", it.message)
        false
    }

    /** 트레이가 없을 때의 최후 수단. 이 창을 닫으면 서버도 함께 내려간다. */
    private fun installFallbackWindow(url: String) {
        runCatching {
            SwingUtilities.invokeLater {
                val frame = JFrame("Orbit")
                frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) = quit()
                })
                frame.iconImage = appIcon(64)
                frame.layout = BorderLayout(12, 12)
                frame.add(
                    JLabel(
                        "<html><div style='text-align:center'>Orbit を実行中です（$url）<br>" +
                            "この小さなウィンドウを閉じると Orbit も終了します。</div></html>",
                        SwingConstants.CENTER,
                    ),
                    BorderLayout.CENTER,
                )
                frame.add(
                    JPanel().apply {
                        add(JButton("ブラウザーで開く").apply { addActionListener { BrowserOpener.open(url) } })
                        add(JButton("終了").apply { addActionListener { quit() } })
                    },
                    BorderLayout.SOUTH,
                )
                frame.setSize(460, 170)
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
                fallbackWindow = frame
            }
        }
    }

    /**
     * 아이콘을 코드로 그린다. 이미지 파일을 리소스에 두면 되지만, 그러면 프론트
     * 담당이 작업 중인 `static/` 과 자리를 두고 다툴 여지가 생긴다. 트레이 아이콘은
     * 어차피 16px 짜리라 도형 두 개로 충분하다.
     */
    private fun appIcon(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1F, 0x2A, 0x44)
        g.fillOval(0, 0, size - 1, size - 1)
        g.color = Color(0xF2, 0xC9, 0x6B)
        val inset = (size * 0.28).toInt().coerceAtLeast(2)
        g.fillOval(inset, inset, size - inset * 2, size - inset * 2)
        if (size >= 32) {
            g.color = Color(0x1F, 0x2A, 0x44)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * 0.36).toInt())
            val fm = g.fontMetrics
            val text = "O"
            g.drawString(text, (size - fm.stringWidth(text)) / 2, (size + fm.ascent - fm.descent) / 2)
        }
        g.dispose()
        return image
    }
}

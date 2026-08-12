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
        val url = "http://localhost:$port"

        SingleInstance.publishPort(port)
        // 정상 종료든 강제 종료든 포트 파일이 남아 있으면 다음 실행이 헛다리를 짚는다.
        Runtime.getRuntime().addShutdownHook(Thread { SingleInstance.releasePort() })

        log.info("Orbit 준비 완료 — {}", url)

        if (!installTray(url)) {
            installFallbackWindow(url)
        }
        installMacQuitHandler()

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

        val menu = PopupMenu()
        menu.add(MenuItem("Orbit 열기").apply { addActionListener { BrowserOpener.open(url) } })
        menu.addSeparator()
        menu.add(MenuItem("종료").apply { addActionListener { quit() } })

        val icon = TrayIcon(appIcon(tray.trayIconSize.width.coerceAtLeast(16)), "Orbit — $url", menu)
        icon.isImageAutoSize = true
        icon.addActionListener { BrowserOpener.open(url) }
        tray.add(icon)
        trayIcon = icon
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
                frame.add(JLabel("Orbit 이 실행 중입니다  ($url)", SwingConstants.CENTER), BorderLayout.CENTER)
                frame.add(
                    JPanel().apply {
                        add(JButton("브라우저 열기").apply { addActionListener { BrowserOpener.open(url) } })
                        add(JButton("종료").apply { addActionListener { quit() } })
                    },
                    BorderLayout.SOUTH,
                )
                frame.setSize(420, 150)
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

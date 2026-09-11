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
 * 데스크톱 모드 전용. 서버가 뜨면 포트를 기록하고 브라우저를 열고 종료 수단을 만든다.
 * 브라우저 탭을 닫아도 서버는 남으므로 트레이 아이콘(불가하면 작은 창)으로 종료하게 한다.
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
        // 서버는 IPv4 루프백에만 바인딩되는데 localhost 는 ::1 로 먼저 풀릴 수 있다
        val url = "http://127.0.0.1:$port"

        SingleInstance.publishPort(port)
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

    // --- 종료

    /**
     * H2 의 마지막 쓰기가 유실되지 않도록 스프링 컨텍스트를 먼저 닫고 프로세스를 끝낸다.
     * AWT 이벤트 스레드가 멎지 않도록 별도 스레드에서 실행한다.
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
                response.cancelQuit() // 정리 후 quit() 에서 직접 종료
                quit()
            }
        }
    }

    // --- 트레이

    private fun installTray(url: String): Boolean = runCatching {
        if (!SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()

        // 사용자에게 보이는 문구라 일본어로 쓴다
        val menu = PopupMenu()
        menu.add(MenuItem("Orbit を開く").apply { addActionListener { BrowserOpener.open(url) } })
        menu.addSeparator()
        menu.add(MenuItem("終了 (Orbit を閉じる)").apply { addActionListener { quit() } })

        val icon = TrayIcon(appIcon(tray.trayIconSize.width.coerceAtLeast(16)), "Orbit — $url", menu)
        icon.isImageAutoSize = true
        icon.addActionListener { BrowserOpener.open(url) }
        tray.add(icon)
        trayIcon = icon

        // 탭을 닫아도 종료되지 않는다는 점을 기동 시 한 번 안내한다
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

    /** 트레이를 못 쓸 때의 대체 수단. 창을 닫으면 서버도 종료된다. */
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

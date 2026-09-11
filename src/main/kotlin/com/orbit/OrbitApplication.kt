package com.orbit

import com.orbit.runtime.DesktopRuntime
import com.orbit.runtime.FreePort
import com.orbit.runtime.SecondInstance
import com.orbit.runtime.SingleInstance
import com.orbit.service.OwnerProperties
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
// JwtProperties 는 SecurityConfig 가 등록한다
@EnableConfigurationProperties(OwnerProperties::class)
class OrbitApplication

/**
 * 개발 실행(bootRun, IDE)은 스프링만 띄우고, jpackage 설치본은 트레이·브라우저·포트 선택까지 켠다.
 * 설치본 여부는 jpackage 런처가 항상 넣는 `jpackage.app-path` 로 판단한다.
 */
fun main(args: Array<String>) {
    if (DesktopRuntime.launchedFromPackagedApp()) DesktopRuntime.enableUnlessOverridden()

    if (!DesktopRuntime.enabled) {
        runApplication<OrbitApplication>(*args)
        return
    }

    // 스프링이 headless 를 true 로 넣으면 SystemTray 가 죽어 종료 수단이 없어지므로 먼저 지정한다
    System.setProperty("java.awt.headless", "false")

    // H2 파일 DB 는 한 프로세스만 열 수 있다.
    // 떠 있는 인스턴스가 다른 빌드면 교체하고, 같은 빌드면 화면만 열어 주고 종료한다.
    if (!SingleInstance.acquire() && !SecondInstance.replaceOutdatedInstance()) {
        SecondInstance.handOverToRunningInstance()
        exitProcess(0)
    }

    // 기본 프로퍼티나 EnvironmentPostProcessor 로는 yml 의 server.port 를 못 이기므로 커맨드라인 인자로 넘긴다
    val arguments = if (args.any { it.startsWith("--server.port") }) {
        args
    } else {
        args + "--server.port=${FreePort.pick(DesktopRuntime.preferredPort())}"
    }

    SpringApplication(OrbitApplication::class.java)
        .apply { setHeadless(false) }
        .run(*arguments)
}

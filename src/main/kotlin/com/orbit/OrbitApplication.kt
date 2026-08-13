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
// 주인 정보는 설정에서 온다. JwtProperties 는 SecurityConfig 가 등록하고,
// 이 값은 특정 설정 클래스에 딸린 것이 아니라 앱 전체의 것이라 여기서 켠다.
@EnableConfigurationProperties(OwnerProperties::class)
class OrbitApplication

/**
 * 두 갈래로 뜬다.
 *
 *  - **개발 실행**(`./gradlew bootRun`, IDE) — 예전 그대로 스프링만 띄운다. 트레이
 *    아이콘도 브라우저 자동 실행도 없다. 옆에서 화면 작업하는 사람의 8080 을
 *    빼앗지 않도록 포트도 건드리지 않는다.
 *  - **설치본 실행**(jpackage 로 만든 exe) — 데스크톱 앱으로서 필요한 것들을 켠다.
 *
 * 구분은 `jpackage.app-path` 로 한다. jpackage 가 만든 런처는 이 시스템 프로퍼티를
 * 항상 넣어 주므로 "포장된 실행 파일로 떴는가"의 가장 정직한 신호다. 런처 설정에도
 * `-Dorbit.desktop=true` 를 함께 박아 두지만(둘 중 하나만 살아남아도 동작하게),
 * 판단의 근거를 실행 방식 자체에 두는 편이 설정 파일 한 줄이 빠졌을 때 조용히
 * 망가지지 않는다.
 */
fun main(args: Array<String>) {
    if (DesktopRuntime.launchedFromPackagedApp()) DesktopRuntime.enableUnlessOverridden()

    if (!DesktopRuntime.enabled) {
        runApplication<OrbitApplication>(*args)
        return
    }

    // 트레이 아이콘과 브라우저 열기가 AWT 위에서 돈다. 스프링은 웹 앱을 띄울 때
    // `java.awt.headless` 를 true 로 밀어 넣는데(이미 값이 있으면 존중한다), 그러면
    // SystemTray 가 통째로 죽어서 **사용자에게 종료 수단이 없어진다.** 그래서 스프링이
    // 손대기 전에 여기서 먼저 못 박는다. 아래 setHeadless(false) 는 같은 말의 이중 방어다.
    System.setProperty("java.awt.headless", "false")

    // 두 번째 실행은 실패시키지 않는다. H2 파일 DB 는 한 프로세스만 열 수 있어서
    // 그냥 두면 "아이콘을 눌렀는데 아무 일도 안 일어났다"가 된다. 이미 떠 있는
    // 쪽의 화면을 열어 주고 조용히 물러난다.
    if (!SingleInstance.acquire()) {
        SecondInstance.handOverToRunningInstance()
        exitProcess(0)
    }

    // 포트는 **커맨드라인 인자**로 넘긴다. application.yml 의 `server.port: 8080` 을
    // 이겨야 하는데, 기본 프로퍼티나 EnvironmentPostProcessor 로 넣으면 우선순위에서
    // yml 에 진다. 사용자가 직접 포트를 준 경우는 그대로 존중한다.
    val arguments = if (args.any { it.startsWith("--server.port") }) {
        args
    } else {
        args + "--server.port=${FreePort.pick(DesktopRuntime.preferredPort())}"
    }

    SpringApplication(OrbitApplication::class.java)
        .apply { setHeadless(false) }
        .run(*arguments)
}

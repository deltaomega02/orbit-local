plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
    kotlin("plugin.jpa") version "2.2.0"
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.orbit"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JWT. impl/jackson 은 런타임에만 필요하므로 컴파일 클래스패스에서 뺀다 —
    // 애플리케이션 코드가 jjwt 내부 구현 클래스에 실수로 의존하는 것을 막는다.
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * jpackage 에 넘길 입력 디렉터리를 만든다.
 *
 * jpackage 의 `--input` 은 **그 폴더 안의 모든 파일을 앱 안으로 복사한다.** 그래서
 * `build/libs` 를 그대로 가리키면 실행 가능한 부트 jar 옆에 아무 쓸모 없는
 * `-plain.jar`(클래스만 든 껍데기)까지 같이 들어간다. 넣을 것 하나만 따로 모은다.
 *
 * 파일 이름은 `orbit.jar` 로 고정한다. 버전이 이름에 박혀 있으면 릴리스마다 CI
 * 스크립트의 `--main-jar` 를 같이 고쳐야 하고, 그건 잊어버리기 딱 좋은 자리다.
 */
tasks.register<Sync>("packagingInput") {
    group = "distribution"
    description = "jpackage 입력(실행 가능한 jar 하나)을 build/packaging/input 에 모은다"

    from(tasks.named("bootJar")) { rename { "orbit.jar" } }
    into(layout.buildDirectory.dir("packaging/input"))
}

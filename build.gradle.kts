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

    // EXIF 방향 태그 읽기 전용. 세그먼트 구성·엔디안이 제각각이라 직접 파싱하지 않는다.
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // JWT. impl/jackson 은 runtimeOnly 로 둬서 내부 구현 클래스에 의존하지 않게 한다.
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
 * jpackage --input 디렉터리. --input 은 폴더 전체를 복사하므로 -plain.jar 를 빼고 부트 jar 만 모은다.
 * 이름을 orbit.jar 로 고정해 CI 의 --main-jar 를 버전마다 고치지 않게 한다.
 */
tasks.register<Sync>("packagingInput") {
    group = "distribution"
    description = "jpackage 입력(실행 가능한 jar 하나)을 build/packaging/input 에 모은다"

    from(tasks.named("bootJar")) { rename { "orbit.jar" } }
    into(layout.buildDirectory.dir("packaging/input"))
}

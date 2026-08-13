package com.orbit

import com.orbit.service.OwnerProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
// 주인 정보는 설정에서 온다. JwtProperties 는 SecurityConfig 가 등록하고,
// 이 값은 특정 설정 클래스에 딸린 것이 아니라 앱 전체의 것이라 여기서 켠다.
@EnableConfigurationProperties(OwnerProperties::class)
class OrbitApplication

fun main(args: Array<String>) {
    runApplication<OrbitApplication>(*args)
}

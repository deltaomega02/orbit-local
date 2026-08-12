package com.orbit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OrbitApplication

fun main(args: Array<String>) {
    runApplication<OrbitApplication>(*args)
}

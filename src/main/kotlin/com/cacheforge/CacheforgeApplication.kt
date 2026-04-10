package com.cacheforge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CacheforgeApplication

fun main(args: Array<String>) {
    runApplication<CacheforgeApplication>(*args)
}

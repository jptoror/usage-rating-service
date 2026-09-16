package com.revenium.usage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class UsageRatingApplication

fun main(args: Array<String>) {
    runApplication<UsageRatingApplication>(*args)
}

package com.convertic.akuflow

import io.temporal.spring.boot.Temporal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@Temporal
class AkuflowApplication

fun main(args: Array<String>) {
    runApplication<AkuflowApplication>(*args)
}

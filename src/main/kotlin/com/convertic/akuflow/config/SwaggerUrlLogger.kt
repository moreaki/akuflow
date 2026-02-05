package com.convertic.akuflow.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class SwaggerUrlLogger(
    private val env: Environment
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun logSwaggerUrl() {
        val port = env.getProperty("local.server.port")
            ?: env.getProperty("server.port")
            ?: "8080"
        val contextPath = env.getProperty("server.servlet.context-path") ?: ""
        val url = "http://localhost:$port$contextPath/swagger-ui/index.html"
        log.info("Swagger UI: {}", url)
    }
}

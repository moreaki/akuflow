package com.convertic.akuflow.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class DatasourceInfoLogger(
    private val env: Environment
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun logDatasourceInfo() {
        val url = env.getProperty("spring.datasource.url") ?: "n/a"
        val user = env.getProperty("spring.datasource.username") ?: "n/a"
        val driver = env.getProperty("spring.datasource.driver-class-name")
        if (driver.isNullOrBlank()) {
            log.info("Datasource: url={}, user={}", url, user)
        } else {
            log.info("Datasource: url={}, user={}, driver={}", url, user, driver)
        }
    }
}

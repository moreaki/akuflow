package com.convertic.akuflow.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AkuflowManagementProperties::class)
class AkuflowManagementConfig

@ConfigurationProperties(prefix = "akuflow.management")
data class AkuflowManagementProperties(
    var terminateWorkflowsOnShutdown: Boolean = false
)

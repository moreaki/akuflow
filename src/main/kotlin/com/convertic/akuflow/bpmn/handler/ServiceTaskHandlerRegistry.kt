package com.convertic.akuflow.bpmn.handler

import org.springframework.stereotype.Component

@Component
class ServiceTaskHandlerRegistry(
    handlers: List<ServiceTaskHandler>
) {
    private val handlersByKey = handlers.associateBy { it.key }

    fun get(key: String): ServiceTaskHandler =
        handlersByKey[key] ?: error("No ServiceTaskHandler registered for key='$key'")
}

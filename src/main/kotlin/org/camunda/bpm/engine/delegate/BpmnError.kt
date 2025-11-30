package org.camunda.bpm.engine.delegate

class BpmnError(
    val errorCode: String,
    message: String? = null
) : RuntimeException(message)

package com.convertic.akuflow.bpmn.runtime

class BpmnBusinessError(
    val errorCode: String,
    message: String? = null
) : RuntimeException(message)

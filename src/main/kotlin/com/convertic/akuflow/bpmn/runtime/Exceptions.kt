package com.convertic.akuflow.bpmn.runtime

class DefinitionNotFoundException(message: String) : RuntimeException(message)

class BpmnCompilationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

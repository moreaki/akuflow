package com.convertic.akuflow.bpmn.handler

interface ServiceTaskHandler {
    val key: String
    fun handle(vars: MutableMap<String, Any?>)
}

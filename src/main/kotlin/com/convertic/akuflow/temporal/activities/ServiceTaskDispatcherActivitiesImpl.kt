package com.convertic.akuflow.temporal.activities

import com.convertic.akuflow.bpmn.handler.ServiceTaskHandlerRegistry
import com.convertic.akuflow.bpmn.runtime.BpmnBusinessError
import io.temporal.spring.boot.ActivityImpl
import org.camunda.bpm.engine.delegate.BpmnError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component("serviceTaskDispatcherActivitiesImpl")
@ActivityImpl(taskQueues = ["akuflow-bpmn"])
class ServiceTaskDispatcherActivitiesImpl(
    private val registry: ServiceTaskHandlerRegistry
) : ServiceTaskDispatcherActivities {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(handlerKey: String, vars: MutableMap<String, Any?>): MutableMap<String, Any?> {
        log.debug("Executing handlerKey={}", handlerKey)
        val handler = registry.get(handlerKey)
        try {
            handler.handle(vars)
        } catch (be: BpmnError) {
            throw BpmnBusinessError(be.errorCode, be.message)
        }
        return vars
    }
}

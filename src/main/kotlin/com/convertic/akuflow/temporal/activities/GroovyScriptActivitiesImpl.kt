package com.convertic.akuflow.temporal.activities

import com.convertic.akuflow.bpmn.runtime.BpmnBusinessError
import groovy.lang.Binding
import groovy.lang.GroovyShell
import io.temporal.spring.boot.ActivityImpl
import org.camunda.bpm.engine.delegate.BpmnError
import org.springframework.stereotype.Component

@Component("groovyScriptActivitiesImpl")
@ActivityImpl(taskQueues = ["akuflow-bpmn"])
class GroovyScriptActivitiesImpl : GroovyScriptActivities {

    override fun executeGroovy(
        script: String,
        vars: MutableMap<String, Any?>,
        handlerKey: String
    ): MutableMap<String, Any?> {

        val binding = Binding()
        val execution = ExecutionFacade(vars)

        binding.setVariable("execution", execution)
        binding.setVariable("vars", vars)

        // expose existing vars as top-level variables
        vars.forEach { (k, v) -> binding.setVariable(k, v) }

        val shell = GroovyShell(binding)
        try {
            shell.evaluate(script)
        } catch (be: BpmnError) {
            throw BpmnBusinessError(be.errorCode, be.message)
        }

        // sync all binding vars back
        binding.variables.forEach { (k, v) -> vars[k] = v }

        return vars
    }
}

class ExecutionFacade(
    private val vars: MutableMap<String, Any?>
) {
    fun getVariable(name: String): Any? = vars[name]
    fun setVariable(name: String, value: Any?) { vars[name] = value }
}

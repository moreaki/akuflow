package com.convertic.akuflow.temporal.activities

import com.convertic.akuflow.bpmn.model.CompiledProcess
import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component

@Component("definitionLookupActivitiesImpl")
@ActivityImpl(taskQueues = ["akuflow-bpmn"])
class DefinitionLookupActivitiesImpl(
    private val definitionService: BpmnDefinitionService
) : DefinitionLookupActivities {

    override fun loadCompiledProcess(processKey: String): CompiledProcess =
        definitionService.latestActive(processKey)
}

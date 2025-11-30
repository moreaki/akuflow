package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import com.convertic.akuflow.temporal.workflows.BpmnWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/cases")
class CaseController(
    private val definitionService: BpmnDefinitionService,
    private val workflowClient: WorkflowClient
) {

    data class StartCaseRequest(
        val processKey: String,
        val initialVars: Map<String, Any?> = emptyMap()
    )

    data class StartCaseResponse(
        val workflowId: String,
        val processKey: String,
        val version: Int
    )

    @PostMapping
    fun startCase(@RequestBody req: StartCaseRequest): StartCaseResponse {
        val def = definitionService.latestActive(req.processKey)
        val workflowId = "${def.processKey}-${def.version}-${UUID.randomUUID()}"

        val options = WorkflowOptions.newBuilder()
            .setTaskQueue("akuflow-bpmn")
            .setWorkflowId(workflowId)
            .build()

        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, options)
        WorkflowClient.start(stub::run, def, req.initialVars)

        return StartCaseResponse(
            workflowId = workflowId,
            processKey = def.processKey,
            version = def.version
        )
    }
}

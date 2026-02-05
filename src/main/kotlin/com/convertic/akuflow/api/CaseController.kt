package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import com.convertic.akuflow.temporal.workflows.BpmnWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping(
    value = ["/api/cases"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class CaseController(
    private val definitionService: BpmnDefinitionService,
    private val workflowClient: WorkflowClient
) {

    data class StartCaseRequest(
        val processKey: String,
        val version: Int? = null,
        val initialVars: Map<String, Any?> = emptyMap()
    )

    data class StartCaseResponse(
        val workflowId: String,
        val processKey: String,
        val version: Int
    )

    data class WorkflowStatusResponse(
        val workflowId: String,
        val runId: String?,
        val status: String,
        val workflowType: String?,
        val taskQueue: String?,
        val startTime: String?,
        val executionTime: String?,
        val closeTime: String?
    )

    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun startCase(@RequestBody req: StartCaseRequest): StartCaseResponse {
        val def = req.version?.let { definitionService.byVersion(req.processKey, it) }
            ?: definitionService.latestActive(req.processKey)
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

    @GetMapping("/{workflowId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStatus(@PathVariable workflowId: String): WorkflowStatusResponse {
        val stub: WorkflowStub = workflowClient.newUntypedWorkflowStub(workflowId)
        val desc = stub.describe()
        val exec = desc.execution

        return WorkflowStatusResponse(
            workflowId = exec.workflowId,
            runId = exec.runId,
            status = desc.status.name,
            workflowType = desc.workflowType,
            taskQueue = desc.taskQueue,
            startTime = desc.startTime.toString(),
            executionTime = desc.executionTime.toString(),
            closeTime = desc.closeTime?.toString()
        )
    }
}

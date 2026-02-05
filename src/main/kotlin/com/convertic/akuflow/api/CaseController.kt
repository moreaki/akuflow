package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import com.convertic.akuflow.temporal.workflows.BpmnWorkflow
import com.convertic.akuflow.temporal.workflows.BpmnWorkflowState
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowTargetOptions
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

    data class WorkflowStateResponse(
        val workflowId: String,
        val state: BpmnWorkflowState
    )

    data class TerminateRequest(
        val workflowId: String? = null,
        val processKey: String? = null,
        val version: Int? = null,
        val reason: String? = null,
        val allRunning: Boolean? = null
    )

    data class TerminateResult(
        val workflowId: String,
        val runId: String?,
        val status: String,
        val reason: String?
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

    @GetMapping("/{workflowId}/state", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getState(@PathVariable workflowId: String): WorkflowStateResponse {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        val state = stub.getState()
        return WorkflowStateResponse(workflowId = workflowId, state = state)
    }

    @GetMapping("/find", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun findLatest(
        @RequestParam processKey: String,
        @RequestParam version: Int
    ): WorkflowStatusResponse {
        val prefix = "$processKey-$version-"
        val query = "WorkflowId STARTS_WITH \"$prefix\""
        val namespace = workflowClient.options.namespace

        val request = ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setQuery(query)
            .setPageSize(100)
            .build()

        val response = workflowClient.workflowServiceStubs
            .blockingStub()
            .listWorkflowExecutions(request)

        val latest = response.executionsList
            .maxByOrNull { it.startTime.seconds }
            ?: throw WorkflowRunNotFoundException(
                "No workflow executions found for processKey=$processKey and version=$version"
            )

        return WorkflowStatusResponse(
            workflowId = latest.execution.workflowId,
            runId = latest.execution.runId,
            status = latest.status.name,
            workflowType = latest.type.name,
            taskQueue = latest.taskQueue,
            startTime = latest.startTime.toString(),
            executionTime = latest.executionTime.toString(),
            closeTime = if (latest.hasCloseTime()) latest.closeTime.toString() else null
        )
    }

    @PostMapping("/terminate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun terminate(@RequestBody req: TerminateRequest): List<TerminateResult> {
        if (req.allRunning == true) {
            if (!req.workflowId.isNullOrBlank() || !req.processKey.isNullOrBlank()) {
                throw IllegalArgumentException("allRunning cannot be combined with workflowId or processKey")
            }
        } else if (req.workflowId.isNullOrBlank() && req.processKey.isNullOrBlank()) {
            throw IllegalArgumentException("Either workflowId or processKey must be provided")
        }
        if (!req.processKey.isNullOrBlank() && req.version == null) {
            throw IllegalArgumentException("version is required when using processKey")
        }

        val reason = req.reason ?: "Terminated by request"

        return when {
            req.allRunning == true -> terminateAllRunning(reason)
            !req.workflowId.isNullOrBlank() -> {
                val stub: WorkflowStub = workflowClient.newUntypedWorkflowStub(req.workflowId)
                stub.terminate(reason)
                listOf(TerminateResult(req.workflowId, null, "TERMINATED", reason))
            }
            else -> terminateByProcessKey(req.processKey!!, req.version!!, reason)
        }
    }

    private fun terminateByProcessKey(
        processKey: String,
        version: Int,
        reason: String
    ): List<TerminateResult> {
        val prefix = "$processKey-$version-"
        val query = "WorkflowId STARTS_WITH \"$prefix\" AND ExecutionStatus=\"Running\""
        val namespace = workflowClient.options.namespace

        val request = ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setQuery(query)
            .setPageSize(1000)
            .build()

        val response = workflowClient.workflowServiceStubs
            .blockingStub()
            .listWorkflowExecutions(request)

        val executions = response.executionsList
        if (executions.isEmpty()) {
            throw WorkflowRunNotFoundException(
                "No running workflows found for processKey=$processKey and version=$version"
            )
        }

        return executions.map { info ->
            val exec = info.execution
            val target = WorkflowTargetOptions.newBuilder()
                .setWorkflowId(exec.workflowId)
                .setRunId(exec.runId)
                .build()
            val stub: WorkflowStub = workflowClient.newUntypedWorkflowStub(target)
            stub.terminate(reason)
            TerminateResult(exec.workflowId, exec.runId, "TERMINATED", reason)
        }
    }

    private fun terminateAllRunning(reason: String): List<TerminateResult> {
        val namespace = workflowClient.options.namespace
        val query = "TaskQueue=\"akuflow-bpmn\" AND ExecutionStatus=\"Running\""

        val request = ListWorkflowExecutionsRequest.newBuilder()
            .setNamespace(namespace)
            .setQuery(query)
            .setPageSize(1000)
            .build()

        val response = workflowClient.workflowServiceStubs
            .blockingStub()
            .listWorkflowExecutions(request)

        val executions = response.executionsList
        if (executions.isEmpty()) {
            return emptyList()
        }

        return executions.map { info ->
            val exec = info.execution
            val target = WorkflowTargetOptions.newBuilder()
                .setWorkflowId(exec.workflowId)
                .setRunId(exec.runId)
                .build()
            val stub: WorkflowStub = workflowClient.newUntypedWorkflowStub(target)
            stub.terminate(reason)
            TerminateResult(exec.workflowId, exec.runId, "TERMINATED", reason)
        }
    }
}

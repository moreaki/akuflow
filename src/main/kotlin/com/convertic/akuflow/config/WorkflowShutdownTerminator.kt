package com.convertic.akuflow.config

import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowTargetOptions
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class WorkflowShutdownTerminator(
    private val workflowClient: WorkflowClient,
    private val props: AkuflowManagementProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ContextClosedEvent::class)
    fun onShutdown() {
        if (!props.terminateWorkflowsOnShutdown) {
            log.info("Workflow termination on shutdown is disabled.")
            return
        }

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
            log.info("No running workflows to terminate on shutdown.")
            return
        }

        log.warn("Terminating {} running workflows on shutdown.", executions.size)
        executions.forEach { info ->
            val exec = info.execution
            try {
                val target = WorkflowTargetOptions.newBuilder()
                    .setWorkflowId(exec.workflowId)
                    .setRunId(exec.runId)
                    .build()
                val stub: WorkflowStub = workflowClient.newUntypedWorkflowStub(target)
                stub.terminate("Akuflow shutdown")
            } catch (e: Exception) {
                log.warn(
                    "Failed to terminate workflowId={}, runId={}: {}",
                    exec.workflowId,
                    exec.runId,
                    e.message
                )
            }
        }
    }
}

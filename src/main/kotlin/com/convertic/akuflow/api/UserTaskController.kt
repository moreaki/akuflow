package com.convertic.akuflow.api

import com.convertic.sigma.temporal.workflows.BpmnWorkflow
import io.temporal.client.WorkflowClient
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/cases")
class UserTaskController(
    private val workflowClient: WorkflowClient
) {

    data class UserTaskCompletionRequest(val payload: Map<String, Any?>)
    data class SignalRequest(val name: String, val payload: Map<String, Any?>)
    data class MessageRequest(val name: String, val payload: Map<String, Any?>)

    @PostMapping("/{workflowId}/user-tasks/{taskId}")
    fun completeUserTask(
        @PathVariable workflowId: String,
        @PathVariable taskId: String,
        @RequestBody body: UserTaskCompletionRequest
    ) {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.completeUserTask(taskId, body.payload)
    }

    @PostMapping("/{workflowId}/signals")
    fun sendSignal(
        @PathVariable workflowId: String,
        @RequestBody body: SignalRequest
    ) {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.receiveSignal(body.name, body.payload)
    }

    @PostMapping("/{workflowId}/messages")
    fun sendMessage(
        @PathVariable workflowId: String,
        @RequestBody body: MessageRequest
    ) {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.receiveMessage(body.name, body.payload)
    }
}

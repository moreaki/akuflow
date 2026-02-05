package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.model.UserTaskFormField
import com.convertic.akuflow.temporal.workflows.BpmnWorkflow
import io.temporal.client.WorkflowClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(
    value = ["/api/cases"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class UserTaskController(
    private val workflowClient: WorkflowClient
) {

    data class UserTaskCompletionRequest(val payload: Map<String, Any?>)
    data class SignalRequest(val name: String, val payload: Map<String, Any?>)
    data class MessageRequest(val name: String, val payload: Map<String, Any?>)
    data class AckResponse(val status: String = "ok")
    data class UserTaskInfoResponse(
        val workflowId: String,
        val taskId: String,
        val name: String,
        val formKey: String?,
        val formFields: List<UserTaskFormField>,
        val isPending: Boolean
    )

    @GetMapping(
        "/{workflowId}/user-tasks/{taskId}",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getUserTaskInfo(
        @PathVariable workflowId: String,
        @PathVariable taskId: String
    ): UserTaskInfoResponse {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        val info = stub.getUserTaskInfo(taskId)
            ?: throw UserTaskNotFoundException("User task not found: $taskId")
        val state = stub.getState()

        return UserTaskInfoResponse(
            workflowId = workflowId,
            taskId = info.taskId,
            name = info.name,
            formKey = info.formKey,
            formFields = info.formFields,
            isPending = state.pendingUserTaskId == taskId
        )
    }

    @PostMapping(
        "/{workflowId}/user-tasks/{taskId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun completeUserTask(
        @PathVariable workflowId: String,
        @PathVariable taskId: String,
        @RequestBody body: UserTaskCompletionRequest
    ): AckResponse {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.completeUserTask(taskId, body.payload)
        return AckResponse()
    }

    @PostMapping(
        "/{workflowId}/signals",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun sendSignal(
        @PathVariable workflowId: String,
        @RequestBody body: SignalRequest
    ): AckResponse {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.receiveSignal(body.name, body.payload)
        return AckResponse()
    }

    @PostMapping(
        "/{workflowId}/messages",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun sendMessage(
        @PathVariable workflowId: String,
        @RequestBody body: MessageRequest
    ): AckResponse {
        val stub = workflowClient.newWorkflowStub(BpmnWorkflow::class.java, workflowId)
        stub.receiveMessage(body.name, body.payload)
        return AckResponse()
    }
}

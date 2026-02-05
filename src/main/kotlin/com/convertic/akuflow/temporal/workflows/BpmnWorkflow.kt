package com.convertic.akuflow.temporal.workflows

import com.convertic.akuflow.bpmn.model.CompiledProcess
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface BpmnWorkflow {

    @WorkflowMethod
    fun run(definition: CompiledProcess, initialVars: Map<String, Any?>)

    @SignalMethod
    fun completeUserTask(taskId: String, payload: Map<String, Any?>)

    @SignalMethod
    fun receiveSignal(signalName: String, payload: Map<String, Any?>)

    @SignalMethod
    fun receiveMessage(messageName: String, payload: Map<String, Any?>)

    @QueryMethod
    fun getState(): BpmnWorkflowState
}

data class BpmnWorkflowState(
    val status: String,
    val currentNodeId: String?,
    val pendingUserTaskId: String?,
    val pendingSignals: Int,
    val pendingMessages: Int
)

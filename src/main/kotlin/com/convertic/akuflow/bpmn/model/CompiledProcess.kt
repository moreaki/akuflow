package com.convertic.akuflow.bpmn.model

data class CompiledProcess(
    val processKey: String,
    val version: Int,
    val startNodeId: String,
    val nodes: List<Node>,
    val transitions: List<Transition>,
    val eventSubprocesses: List<EventSubprocess> = emptyList()
)

data class EventSubprocess(
    val id: String,
    val startEventType: EventStartType,
    val startEventRef: String,
    val nodes: List<Node>,
    val transitions: List<Transition>
)

enum class EventStartType { TIMER, MESSAGE, SIGNAL }

sealed interface Node {
    val id: String
    val name: String
}

data class StartNode(
    override val id: String,
    override val name: String
) : Node

data class EndNode(
    override val id: String,
    override val name: String,
    val terminate: Boolean = false
) : Node

data class UserTaskFormField(
    val id: String,
    val label: String?,
    val type: String?,
    val defaultValue: String?,
    val required: Boolean,
    val readOnly: Boolean,
    val properties: Map<String, String> = emptyMap()
)

data class UserTaskNode(
    override val id: String,
    override val name: String,
    val formKey: String? = null,
    val assigneeRole: String? = null,
    val formFields: List<UserTaskFormField> = emptyList()
) : Node

data class ServiceTaskNode(
    override val id: String,
    override val name: String,
    val handlerKey: String
) : Node

data class ScriptTaskNode(
    override val id: String,
    override val name: String,
    val scriptFormat: String,
    val script: String,
    val handlerKey: String? = null
) : Node

data class ExclusiveGatewayNode(
    override val id: String,
    override val name: String
) : Node

data class ParallelGatewayNode(
    override val id: String,
    override val name: String
) : Node

data class CallActivityNode(
    override val id: String,
    override val name: String,
    val calledProcessKey: String,
    val inMappings: List<VariableMapping>,
    val outMappings: List<VariableMapping>
) : Node

data class VariableMapping(
    val source: String,
    val target: String,
    val allVariables: Boolean = false
)

data class TimerBoundaryNode(
    override val id: String,
    override val name: String,
    val attachedToTaskId: String,
    val durationExpression: String
) : Node

data class Transition(
    val fromId: String,
    val toId: String,
    val conditionExpression: String? = null,
    val errorCode: String? = null,
    val signalName: String? = null,
    val messageName: String? = null,
    val isDefault: Boolean = false
)

package com.convertic.akuflow.bpmn.compile

import com.convertic.akuflow.bpmn.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.instance.*
import org.springframework.stereotype.Component

@Component
class BpmnCompiler(
    private val objectMapper: ObjectMapper
) {

    /**
     * Minimal, robust compiler:
     * - Start / End events
     * - User tasks (no form metadata yet)
     * - Service tasks
     * - Script tasks
     * - Exclusive & parallel gateways
     * - Call activities (no camunda:in/out yet)
     * - Sequence flows with optional conditions
     * - NO event subprocesses / camunda extensions yet
     */
    fun compile(processKey: String, xml: String, version: Int): CompiledProcess {
        val modelInstance = Bpmn.readModelFromStream(xml.byteInputStream())

        val processes = modelInstance.getModelElementsByType(Process::class.java)
        val process = processes.firstOrNull { it.id == processKey } ?: processes.firstOrNull()
        ?: error("No <process> found in BPMN")

        val startEvent = process.flowElements
            .filterIsInstance<StartEvent>()
            .firstOrNull()
            ?: error("No start event in process ${process.id}")

        val nodes = mutableListOf<Node>()
        val transitions = mutableListOf<Transition>()

        // --- nodes ---
        process.flowElements.forEach { fe ->
            when (fe) {
                is StartEvent -> nodes += StartNode(fe.id, fe.name ?: fe.id)
                is EndEvent -> nodes += EndNode(fe.id, fe.name ?: fe.id)
                is UserTask -> nodes += compileUserTask(fe)
                is ServiceTask -> nodes += compileServiceTask(process, fe)
                is ScriptTask -> nodes += compileScriptTask(process, fe)
                is ExclusiveGateway -> nodes += ExclusiveGatewayNode(fe.id, fe.name ?: fe.id)
                is ParallelGateway -> nodes += ParallelGatewayNode(fe.id, fe.name ?: fe.id)
                is CallActivity -> nodes += compileCallActivity(process, fe)
                // Boundary events, timers, event subprocesses etc. will be added later
            }
        }

        // --- sequence flows ---
        process.flowElements.filterIsInstance<SequenceFlow>().forEach { sf ->
            transitions += Transition(
                fromId = sf.source.id,
                toId = sf.target.id,
                conditionExpression = sf.conditionExpression?.textContent
            )
        }

        // For now we do not compile event subprocesses – keep list empty
        val eventSubprocesses: List<EventSubprocess> = emptyList()

        return CompiledProcess(
            processKey = process.id,
            version = version,
            startNodeId = startEvent.id,
            nodes = nodes,
            transitions = transitions,
            eventSubprocesses = eventSubprocesses
        )
    }

    private fun compileUserTask(ut: UserTask): UserTaskNode {
        // Minimal: ignore form metadata for now
        return UserTaskNode(
            id = ut.id,
            name = ut.name ?: ut.id,
            formKey = ut.formKey,
            assigneeRole = ut.candidateGroups,
            formFields = emptyList()
        )
    }

    private fun resolveHandlerKey(processId: String, fe: FlowNode): String {
        // For now just generate "processId.elementId"
        return "$processId.${fe.id}"
    }

    private fun compileServiceTask(process: Process, st: ServiceTask): ServiceTaskNode {
        val handlerKey = resolveHandlerKey(process.id, st)
        return ServiceTaskNode(
            id = st.id,
            name = st.name ?: st.id,
            handlerKey = handlerKey
        )
    }

    private fun compileScriptTask(process: Process, st: ScriptTask): ScriptTaskNode {
        val script = st.script?.textContent ?: ""
        val scriptFormat = st.scriptFormat ?: "groovy"
        val handlerKey = resolveHandlerKey(process.id, st)
        return ScriptTaskNode(
            id = st.id,
            name = st.name ?: st.id,
            scriptFormat = scriptFormat,
            script = script,
            handlerKey = handlerKey
        )
    }

    private fun compileCallActivity(process: Process, ca: CallActivity): CallActivityNode {
        val calledKey = ca.calledElement ?: error("callActivity ${ca.id} has no calledElement")

        // For now we ignore camunda:in / camunda:out mappings
        return CallActivityNode(
            id = ca.id,
            name = ca.name ?: ca.id,
            calledProcessKey = calledKey,
            inMappings = emptyList(),
            outMappings = emptyList()
        )
    }

    fun toJson(def: CompiledProcess): String =
        objectMapper.writeValueAsString(def)

    fun fromJson(json: String): CompiledProcess =
        objectMapper.readValue(json, CompiledProcess::class.java)
}

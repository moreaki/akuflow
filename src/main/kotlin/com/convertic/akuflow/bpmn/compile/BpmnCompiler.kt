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

        val (nodes, transitions) = compileContainer(process.flowElements, process.id)

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
        // Minimal: ignore form metadata & camunda extensions for now
        return UserTaskNode(
            id = ut.id,
            name = ut.name ?: ut.id,
            formKey = null,
            assigneeRole = null,
            formFields = emptyList()
        )
    }

    private fun resolveHandlerKey(processId: String, fe: FlowNode): String {
        // For now just generate "processId.elementId"
        return "$processId.${fe.id}"
    }

    private fun compileServiceTask(processId: String, st: ServiceTask): ServiceTaskNode {
        val handlerKey = resolveHandlerKey(processId, st)
        return ServiceTaskNode(
            id = st.id,
            name = st.name ?: st.id,
            handlerKey = handlerKey
        )
    }

    private fun compileScriptTask(processId: String, st: ScriptTask): ScriptTaskNode {
        val script = st.script?.textContent ?: ""
        val scriptFormat = st.scriptFormat ?: "groovy"
        val handlerKey = resolveHandlerKey(processId, st)
        return ScriptTaskNode(
            id = st.id,
            name = st.name ?: st.id,
            scriptFormat = scriptFormat,
            script = script,
            handlerKey = handlerKey
        )
    }

    private fun compileCallActivity(ca: CallActivity): CallActivityNode {
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

    private fun compileContainer(
        flowElements: Collection<FlowElement>,
        processId: String
    ): Pair<List<Node>, List<Transition>> {
        val nodes = mutableListOf<Node>()
        val transitions = mutableListOf<Transition>()

        // --- nodes ---
        flowElements.forEach { fe ->
            when (fe) {
                is StartEvent -> nodes += StartNode(fe.id, fe.name ?: fe.id)
                is EndEvent -> nodes += EndNode(fe.id, fe.name ?: fe.id)
                is UserTask -> nodes += compileUserTask(fe)
                is ServiceTask -> nodes += compileServiceTask(processId, fe)
                is ScriptTask -> nodes += compileScriptTask(processId, fe)
                is ExclusiveGateway -> nodes += ExclusiveGatewayNode(fe.id, fe.name ?: fe.id)
                is ParallelGateway -> nodes += ParallelGatewayNode(fe.id, fe.name ?: fe.id)
                is CallActivity -> nodes += compileCallActivity(fe)
                is SubProcess -> nodes += compileSubProcess(fe, processId)
                // Boundary events, timers, event subprocesses etc. will be added later
            }
        }

        // --- sequence flows ---
        flowElements.filterIsInstance<SequenceFlow>().forEach { sf ->
            transitions += Transition(
                fromId = sf.source.id,
                toId = sf.target.id,
                conditionExpression = sf.conditionExpression?.textContent
            )
        }

        return nodes to transitions
    }

    private fun compileSubProcess(sp: SubProcess, processId: String): SubProcessNode {
        val startEvent = sp.flowElements
            .filterIsInstance<StartEvent>()
            .firstOrNull()
            ?: error("No start event in subprocess ${sp.id}")

        val (nodes, transitions) = compileContainer(sp.flowElements, processId)

        return SubProcessNode(
            id = sp.id,
            name = sp.name ?: sp.id,
            startNodeId = startEvent.id,
            nodes = nodes,
            transitions = transitions
        )
    }
}

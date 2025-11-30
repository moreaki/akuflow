package com.convertic.akuflow.bpmn.compile

import com.convertic.akuflow.bpmn.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.instance.*
import org.camunda.bpm.model.bpmn.instance.camunda.*
import org.springframework.stereotype.Component

@Component
class BpmnCompiler(
    private val objectMapper: ObjectMapper
) {

    fun compile(processKey: String, xml: String, version: Int): CompiledProcess {
        val modelInstance = Bpmn.readModelFromStream(xml.byteInputStream())

        val processes = modelInstance.getModelElementsByType(Process::class.java)
        val process = processes.firstOrNull { it.id == processKey } ?: processes.firstOrNull()
        ?: error("No <process> found in BPMN")

        val startEvent = process.flowElements
            .filterIsInstance<StartEvent>()
            .firstOrNull { !it.triggeredByEvent }
            ?: error("No normal start event in process ${process.id}")

        val errorsById = modelInstance
            .getModelElementsByType(Error::class.java)
            .associateBy({ it.id }, { it.errorCode })

        val nodes = mutableListOf<Node>()
        val transitions = mutableListOf<Transition>()

        process.flowElements.forEach { fe ->
            when (fe) {
                is StartEvent -> nodes += StartNode(fe.id, fe.name ?: fe.id)
                is EndEvent -> nodes += EndNode(
                    id = fe.id,
                    name = fe.name ?: fe.id,
                    terminate = fe.eventDefinitions.any { it is TerminateEventDefinition }
                )
                is UserTask -> nodes += compileUserTask(fe)
                is ServiceTask -> nodes += compileServiceTask(process, fe)
                is ScriptTask -> nodes += compileScriptTask(process, fe)
                is ExclusiveGateway -> nodes += ExclusiveGatewayNode(fe.id, fe.name ?: fe.id)
                is ParallelGateway -> nodes += ParallelGatewayNode(fe.id, fe.name ?: fe.id)
                is CallActivity -> nodes += compileCallActivity(process, fe)
                is BoundaryEvent -> {
                    val timerDef = fe.eventDefinitions.filterIsInstance<TimerEventDefinition>().firstOrNull()
                    if (timerDef != null) {
                        val durExpr = timerDef.timeDuration?.textContent ?: ""
                        nodes += TimerBoundaryNode(
                            id = fe.id,
                            name = fe.name ?: fe.id,
                            attachedToTaskId = fe.attachedTo.id,
                            durationExpression = durExpr
                        )
                    }
                }
            }
        }

        process.flowElements.filterIsInstance<SequenceFlow>().forEach { sf ->
            transitions += Transition(
                fromId = sf.source.id,
                toId = sf.target.id,
                conditionExpression = sf.conditionExpression?.textContent
            )
        }

        process.flowElements.filterIsInstance<BoundaryEvent>().forEach { be ->
            val errorDef = be.eventDefinitions.filterIsInstance<ErrorEventDefinition>().firstOrNull()
            val errorCode = errorDef?.error?.let { errorsById[it.id] }
            if (errorCode != null) {
                val attachedToId = be.attachedTo.id
                val outgoing = be.outgoing.firstOrNull() ?: return@forEach
                transitions += Transition(
                    fromId = attachedToId,
                    toId = outgoing.target.id,
                    errorCode = errorCode
                )
            }

            val signalDef = be.eventDefinitions.filterIsInstance<SignalEventDefinition>().firstOrNull()
            if (signalDef != null) {
                val name = signalDef.signal.name
                val outgoing = be.outgoing.firstOrNull() ?: return@forEach
                transitions += Transition(
                    fromId = be.attachedTo.id,
                    toId = outgoing.target.id,
                    signalName = name
                )
            }

            val msgDef = be.eventDefinitions.filterIsInstance<MessageEventDefinition>().firstOrNull()
            if (msgDef != null) {
                val name = msgDef.message.name
                val outgoing = be.outgoing.firstOrNull() ?: return@forEach
                transitions += Transition(
                    fromId = be.attachedTo.id,
                    toId = outgoing.target.id,
                    messageName = name
                )
            }
        }

        val eventSubprocesses = process.flowElements
            .filterIsInstance<SubProcess>()
            .filter { it.triggeredByEvent() }
            .map { compileEventSubprocess(it) }

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
        val formFields = mutableListOf<UserTaskFormField>()
        val ext = ut.extensionElements
        val formData = ext?.elementsQuery
            ?.filterByType(CamundaFormData::class.java)
            ?.singleResult()

        formData?.camundaFormFields?.forEach { ff ->
            val props = ff.properties?.camundaProperties.orEmpty().associate {
                it.camundaId to (it.camundaValue ?: "")
            }
            val required = ff.validation?.constraints?.any { it.camundaName == "required" } == true
            val readOnly = ff.validation?.constraints?.any { it.camundaName == "readonly" } == true
            formFields += UserTaskFormField(
                id = ff.id,
                label = ff.label,
                type = ff.type,
                defaultValue = ff.defaultValue,
                required = required,
                readOnly = readOnly,
                properties = props
            )
        }

        return UserTaskNode(
            id = ut.id,
            name = ut.name ?: ut.id,
            formKey = ut.formKey,
            assigneeRole = ut.candidateGroups,
            formFields = formFields
        )
    }

    private fun resolveHandlerKey(processId: String, fe: FlowNode): String {
        val ext = fe.extensionElements
        if (ext != null) {
            val props = ext.elementsQuery
                .filterByType(CamundaProperties::class.java)
                .singleResult()
            props?.camundaProperties?.forEach { p ->
                if (p.camundaId == "temporalHandlerKey" && !p.camundaValue.isNullOrBlank()) {
                    return p.camundaValue
                }
            }
        }
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

        val inMappings = mutableListOf<VariableMapping>()
        val outMappings = mutableListOf<VariableMapping>()
        val ext = ca.extensionElements

        ext?.elementsQuery?.filterByType(CamundaIn::class.java)?.forEach { cin ->
            inMappings += VariableMapping(
                source = cin.source ?: "",
                target = cin.target ?: cin.targetVariable ?: cin.source ?: "",
                allVariables = cin.isCamundaAll
            )
        }

        ext?.elementsQuery?.filterByType(CamundaOut::class.java)?.forEach { cout ->
            outMappings += VariableMapping(
                source = cout.source ?: "",
                target = cout.target ?: cout.targetVariable ?: cout.source ?: "",
                allVariables = cout.isCamundaAll
            )
        }

        return CallActivityNode(
            id = ca.id,
            name = ca.name ?: ca.id,
            calledProcessKey = calledKey,
            inMappings = inMappings,
            outMappings = outMappings
        )
    }

    private fun compileEventSubprocess(sub: SubProcess): EventSubprocess {
        val nodes = mutableListOf<Node>()
        val transitions = mutableListOf<Transition>()

        val start = sub.flowElements.filterIsInstance<StartEvent>().firstOrNull()
            ?: error("Event subprocess ${sub.id} has no start")

        val startType = when {
            start.eventDefinitions.filterIsInstance<TimerEventDefinition>().isNotEmpty() ->
                EventStartType.TIMER
            start.eventDefinitions.filterIsInstance<MessageEventDefinition>().isNotEmpty() ->
                EventStartType.MESSAGE
            start.eventDefinitions.filterIsInstance<SignalEventDefinition>().isNotEmpty() ->
                EventStartType.SIGNAL
            else -> error("Unsupported event subprocess start type in ${sub.id}")
        }

        sub.flowElements.forEach { fe ->
            when (fe) {
                is StartEvent -> nodes += StartNode(fe.id, fe.name ?: fe.id)
                is EndEvent -> nodes += EndNode(
                    fe.id,
                    fe.name ?: fe.id,
                    terminate = fe.eventDefinitions.any { it is TerminateEventDefinition }
                )
                is UserTask -> nodes += compileUserTask(fe)
                is ServiceTask -> nodes += ServiceTaskNode(fe.id, fe.name ?: fe.id, resolveHandlerKey(sub.id, fe))
                is ScriptTask -> nodes += ScriptTaskNode(
                    fe.id,
                    fe.name ?: fe.id,
                    fe.scriptFormat ?: "groovy",
                    fe.script?.textContent ?: "",
                    resolveHandlerKey(sub.id, fe)
                )
                is ExclusiveGateway -> nodes += ExclusiveGatewayNode(fe.id, fe.name ?: fe.id)
                is ParallelGateway -> nodes += ParallelGatewayNode(fe.id, fe.name ?: fe.id)
            }
        }

        sub.flowElements.filterIsInstance<SequenceFlow>().forEach { sf ->
            transitions += Transition(
                fromId = sf.source.id,
                toId = sf.target.id,
                conditionExpression = sf.conditionExpression?.textContent
            )
        }

        return EventSubprocess(
            id = sub.id,
            startEventType = startType,
            startEventRef = start.id,
            nodes = nodes,
            transitions = transitions
        )
    }

    fun toJson(def: CompiledProcess): String =
        objectMapper.writeValueAsString(def)

    fun fromJson(json: String): CompiledProcess =
        objectMapper.readValue(json, CompiledProcess::class.java)
}

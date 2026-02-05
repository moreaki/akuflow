package com.convertic.akuflow.temporal.workflows

import com.convertic.akuflow.bpmn.expr.ExpressionEvaluator
import com.convertic.akuflow.bpmn.model.*
import com.convertic.akuflow.bpmn.runtime.BpmnBusinessError
import com.convertic.akuflow.temporal.activities.DefinitionLookupActivities
import com.convertic.akuflow.temporal.activities.GroovyScriptActivities
import com.convertic.akuflow.temporal.activities.ServiceTaskDispatcherActivities
import io.temporal.activity.ActivityOptions
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Async
import io.temporal.workflow.Workflow
import java.time.Duration

@WorkflowImpl(taskQueues = ["akuflow-bpmn"])
class BpmnWorkflowImpl : BpmnWorkflow {

    private lateinit var def: CompiledProcess
    private val vars = mutableMapOf<String, Any?>()
    private val exprEval = ExpressionEvaluator()
    private var currentNodeId: String? = null

    private val dispatcher = Workflow.newActivityStub(
        ServiceTaskDispatcherActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build()
    )

    private val groovy = Workflow.newActivityStub(
        GroovyScriptActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build()
    )

    private val definitionLookup = Workflow.newActivityStub(
        DefinitionLookupActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
    )

    private var pendingUserTaskId: String? = null
    private var userTaskPayload: Map<String, Any?>? = null

    private val pendingSignals = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val pendingMessages = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun run(definition: CompiledProcess, initialVars: Map<String, Any?>) {
        this.def = definition
        this.vars.putAll(initialVars)

        val nodeById = def.nodes.associateBy { it.id }

        def.eventSubprocesses.forEach { esp ->
            Async.procedure { runEventSubprocess(esp) }
        }

        var currentId = def.startNodeId

        while (true) {
            currentNodeId = currentId
            val node = nodeById[currentId] ?: error("Unknown node id: $currentId")

            currentId = when (node) {
                is StartNode -> nextSequence(currentId, def.transitions)
                is UserTaskNode -> {
                    waitForUserTask(node)
                    nextSequence(currentId, def.transitions)
                }
                is ServiceTaskNode -> executeServiceTask(node, currentId, def.transitions)
                is ScriptTaskNode -> executeScriptTask(node, currentId, def.transitions)
                is ExclusiveGatewayNode -> routeExclusive(node, def.transitions)
                is ParallelGatewayNode -> routeParallel(node, def.nodes, def.transitions)
                is CallActivityNode -> executeCallActivity(node, currentId, def.transitions)
                is SubProcessNode -> {
                    executeSubProcess(node)
                    nextSequence(currentId, def.transitions)
                }
                is TimerBoundaryNode -> {
                    Workflow.sleep(parseDuration(node.durationExpression))
                    nextSequence(currentId, def.transitions)
                }
                is EndNode -> return
            }
        }
    }

    override fun completeUserTask(taskId: String, payload: Map<String, Any?>) {
        if (pendingUserTaskId == taskId) {
            userTaskPayload = payload
        }
    }

    override fun receiveSignal(signalName: String, payload: Map<String, Any?>) {
        pendingSignals += signalName to payload
    }

    override fun receiveMessage(messageName: String, payload: Map<String, Any?>) {
        pendingMessages += messageName to payload
    }

    override fun getState(): BpmnWorkflowState =
        BpmnWorkflowState(
            status = if (pendingUserTaskId != null && userTaskPayload == null) {
                "WAITING_USER_TASK"
            } else {
                "RUNNING"
            },
            currentNodeId = currentNodeId,
            pendingUserTaskId = pendingUserTaskId,
            pendingSignals = pendingSignals.size,
            pendingMessages = pendingMessages.size
        )

    override fun getUserTaskInfo(taskId: String): BpmnUserTaskInfo? {
        val node = findUserTask(def.nodes, taskId) ?: return null
        return BpmnUserTaskInfo(
            taskId = node.id,
            name = node.name,
            formKey = node.formKey,
            formFields = node.formFields
        )
    }

    private fun waitForUserTask(node: UserTaskNode) {
        pendingUserTaskId = node.id
        userTaskPayload = null
        Workflow.await { userTaskPayload != null }
        vars.putAll(userTaskPayload!!)
        pendingUserTaskId = null
    }

    private fun executeServiceTask(
        node: ServiceTaskNode,
        currentId: String,
        transitions: List<Transition>
    ): String =
        try {
            dispatcher.execute(node.handlerKey, vars)
            nextSequence(currentId, transitions)
        } catch (e: BpmnBusinessError) {
            handleBusinessError(currentId, e.errorCode, transitions)
        }

    private fun executeScriptTask(
        node: ScriptTaskNode,
        currentId: String,
        transitions: List<Transition>
    ): String {
        if (node.scriptFormat.lowercase() != "groovy") {
            throw IllegalArgumentException("Unsupported scriptFormat=${node.scriptFormat}")
        }
        return try {
            groovy.executeGroovy(node.script, vars, node.handlerKey ?: node.id)
            nextSequence(currentId, transitions)
        } catch (e: BpmnBusinessError) {
            handleBusinessError(currentId, e.errorCode, transitions)
        }
    }

    private fun executeCallActivity(
        node: CallActivityNode,
        currentId: String,
        transitions: List<Transition>
    ): String {
        val childVars = mutableMapOf<String, Any?>()
        node.inMappings.forEach { m ->
            if (m.allVariables) {
                childVars.putAll(vars)
            } else {
                childVars[m.target] = vars[m.source]
            }
        }

        val childDef = definitionLookup.loadCompiledProcess(node.calledProcessKey)
        val child = Workflow.newChildWorkflowStub(BpmnWorkflow::class.java)
        Async.procedure { child.run(childDef, childVars) }.get()

        node.outMappings.forEach { m ->
            if (m.allVariables) {
                vars.putAll(childVars)
            } else {
                vars[m.target] = childVars[m.source]
            }
        }

        return nextSequence(currentId, transitions)
    }

    private fun nextSequence(fromId: String, transitions: List<Transition>): String {
        val candidates = transitions.filter {
            it.fromId == fromId && it.errorCode == null && it.signalName == null && it.messageName == null
        }
        require(candidates.isNotEmpty()) { "No outgoing transition from $fromId" }
        require(candidates.size == 1) { "Node $fromId must have exactly one normal outgoing transition (found ${candidates.size})" }
        return candidates.first().toId
    }

    private fun routeExclusive(gw: ExclusiveGatewayNode, transitions: List<Transition>): String {
        val outgoing = transitions.filter { it.fromId == gw.id }

        outgoing.forEach { t ->
            t.conditionExpression?.let { expr ->
                if (exprEval.evaluateBoolean(expr, vars)) {
                    return t.toId
                }
            }
        }
        outgoing.firstOrNull { it.isDefault }?.let { return it.toId }

        return outgoing.firstOrNull()?.toId
            ?: error("No outgoing transition from gateway ${gw.id}")
    }

    private fun routeParallel(
        gw: ParallelGatewayNode,
        nodes: List<Node>,
        transitions: List<Transition>
    ): String {
        val outgoing = transitions.filter { it.fromId == gw.id }
        val branches = outgoing.map { t ->
            Async.procedure { executeBranchFrom(t.toId, nodes, transitions) }
        }
        Workflow.await { branches.all { it.isCompleted } }

        val join = nodes.filterIsInstance<ParallelGatewayNode>()
            .firstOrNull { pn -> transitions.any { it.toId == pn.id && it.fromId != gw.id } }
            ?: error("No join gateway for parallel split ${gw.id}")

        return nextSequence(join.id, transitions)
    }

    private fun executeBranchFrom(startId: String, nodes: List<Node>, transitions: List<Transition>) {
        val nodeById = nodes.associateBy { it.id }
        var currentId = startId

        while (true) {
            val node = nodeById[currentId] ?: return
            currentId = when (node) {
                is StartNode -> nextSequence(currentId, transitions)
                is ServiceTaskNode -> executeServiceTask(node, currentId, transitions)
                is ScriptTaskNode -> executeScriptTask(node, currentId, transitions)
                is UserTaskNode -> {
                    waitForUserTask(node)
                    nextSequence(currentId, transitions)
                }
                is ExclusiveGatewayNode -> routeExclusive(node, transitions)
                is ParallelGatewayNode -> routeParallel(node, nodes, transitions)
                is CallActivityNode -> executeCallActivity(node, currentId, transitions)
                is SubProcessNode -> {
                    executeSubProcess(node)
                    nextSequence(currentId, transitions)
                }
                is EndNode -> return
                else -> return
            }
        }
    }

    private fun handleBusinessError(
        fromNodeId: String,
        errorCode: String?,
        transitions: List<Transition>
    ): String {
        if (errorCode == null) throw IllegalStateException("Business error without code")
        val t = transitions.firstOrNull {
            it.fromId == fromNodeId && it.errorCode == errorCode
        } ?: throw BpmnBusinessError(errorCode, "No boundary transition for errorCode=$errorCode at $fromNodeId")
        return t.toId
    }

    private fun executeSubProcess(sp: SubProcessNode) {
        val nodeById = sp.nodes.associateBy { it.id }
        var currentId = sp.startNodeId

        while (true) {
            currentNodeId = currentId
            val node = nodeById[currentId]
                ?: error("Unknown subprocess node id: $currentId in ${sp.id}")
            currentId = when (node) {
                is StartNode -> nextSequence(currentId, sp.transitions)
                is UserTaskNode -> {
                    waitForUserTask(node)
                    nextSequence(currentId, sp.transitions)
                }
                is ServiceTaskNode -> executeServiceTask(node, currentId, sp.transitions)
                is ScriptTaskNode -> executeScriptTask(node, currentId, sp.transitions)
                is ExclusiveGatewayNode -> routeExclusive(node, sp.transitions)
                is ParallelGatewayNode -> routeParallel(node, sp.nodes, sp.transitions)
                is CallActivityNode -> executeCallActivity(node, currentId, sp.transitions)
                is SubProcessNode -> {
                    executeSubProcess(node)
                    nextSequence(currentId, sp.transitions)
                }
                is TimerBoundaryNode -> {
                    Workflow.sleep(parseDuration(node.durationExpression))
                    nextSequence(currentId, sp.transitions)
                }
                is EndNode -> return
            }
        }
    }

    private fun findUserTask(nodes: List<Node>, taskId: String): UserTaskNode? {
        nodes.forEach { node ->
            when (node) {
                is UserTaskNode -> if (node.id == taskId) return node
                is SubProcessNode -> {
                    val nested = findUserTask(node.nodes, taskId)
                    if (nested != null) return nested
                }
                else -> Unit
            }
        }
        return null
    }

    private fun parseDuration(expr: String): Duration {
        val trimmed = expr.trim()
        return when {
            trimmed.startsWith("\${") -> {
                val varName = trimmed.removePrefix("\${").removeSuffix("}")
                val v = vars[varName]
                when (v) {
                    is Duration -> v
                    is String -> Duration.parse(v)
                    else -> error("Cannot parse duration from $expr")
                }
            }
            else -> Duration.parse(trimmed)
        }
    }

    private fun runEventSubprocess(esp: EventSubprocess) {
        when (esp.startEventType) {
            EventStartType.TIMER -> runTimerEventSubprocess(esp)
            EventStartType.MESSAGE -> runMessageEventSubprocess(esp)
            EventStartType.SIGNAL -> runSignalEventSubprocess(esp)
        }
    }

    private fun runTimerEventSubprocess(esp: EventSubprocess) {
        while (true) {
            Workflow.sleep(Duration.ofSeconds(30))
            // placeholder for event-subprocess logic
        }
    }

    private fun runSignalEventSubprocess(esp: EventSubprocess) {
        while (true) {
            Workflow.await { pendingSignals.isNotEmpty() }
            val (_, payload) = pendingSignals.removeAt(0)
            vars.putAll(payload)
            // placeholder
        }
    }

    private fun runMessageEventSubprocess(esp: EventSubprocess) {
        while (true) {
            Workflow.await { pendingMessages.isNotEmpty() }
            val (_, payload) = pendingMessages.removeAt(0)
            vars.putAll(payload)
            // placeholder
        }
    }
}

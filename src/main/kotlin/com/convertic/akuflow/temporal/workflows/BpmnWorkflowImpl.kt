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
            val node = nodeById[currentId] ?: error("Unknown node id: $currentId")

            currentId = when (node) {
                is StartNode -> nextSequence(currentId)
                is UserTaskNode -> {
                    waitForUserTask(node)
                    nextSequence(currentId)
                }
                is ServiceTaskNode -> executeServiceTask(node, currentId)
                is ScriptTaskNode -> executeScriptTask(node, currentId)
                is ExclusiveGatewayNode -> routeExclusive(node)
                is ParallelGatewayNode -> routeParallel(node)
                is CallActivityNode -> executeCallActivity(node, currentId)
                is TimerBoundaryNode -> {
                    Workflow.sleep(parseDuration(node.durationExpression))
                    nextSequence(currentId)
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

    private fun waitForUserTask(node: UserTaskNode) {
        pendingUserTaskId = node.id
        userTaskPayload = null
        Workflow.await { userTaskPayload != null }
        vars.putAll(userTaskPayload!!)
        pendingUserTaskId = null
    }

    private fun executeServiceTask(node: ServiceTaskNode, currentId: String): String =
        try {
            dispatcher.execute(node.handlerKey, vars)
            nextSequence(currentId)
        } catch (e: BpmnBusinessError) {
            handleBusinessError(currentId, e.errorCode)
        }

    private fun executeScriptTask(node: ScriptTaskNode, currentId: String): String {
        if (node.scriptFormat.lowercase() != "groovy") {
            throw IllegalArgumentException("Unsupported scriptFormat=${node.scriptFormat}")
        }
        return try {
            groovy.executeGroovy(node.script, vars, node.handlerKey ?: node.id)
            nextSequence(currentId)
        } catch (e: BpmnBusinessError) {
            handleBusinessError(currentId, e.errorCode)
        }
    }

    private fun executeCallActivity(node: CallActivityNode, currentId: String): String {
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

        return nextSequence(currentId)
    }

    private fun nextSequence(fromId: String): String {
        val candidates = def.transitions.filter {
            it.fromId == fromId && it.errorCode == null && it.signalName == null && it.messageName == null
        }
        require(candidates.isNotEmpty()) { "No outgoing transition from $fromId" }
        require(candidates.size == 1) { "Node $fromId must have exactly one normal outgoing transition (found ${candidates.size})" }
        return candidates.first().toId
    }

    private fun routeExclusive(gw: ExclusiveGatewayNode): String {
        val outgoing = def.transitions.filter { it.fromId == gw.id }

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

    private fun routeParallel(gw: ParallelGatewayNode): String {
        val outgoing = def.transitions.filter { it.fromId == gw.id }
        val branches = outgoing.map { t ->
            Async.procedure { executeBranchFrom(t.toId) }
        }
        Workflow.await { branches.all { it.isCompleted } }

        val join = def.nodes.filterIsInstance<ParallelGatewayNode>()
            .firstOrNull { pn -> def.transitions.any { it.toId == pn.id && it.fromId != gw.id } }
            ?: error("No join gateway for parallel split ${gw.id}")

        return nextSequence(join.id)
    }

    private fun executeBranchFrom(startId: String) {
        val nodeById = def.nodes.associateBy { it.id }
        var currentId = startId

        while (true) {
            val node = nodeById[currentId] ?: return
            currentId = when (node) {
                is ServiceTaskNode -> executeServiceTask(node, currentId)
                is ScriptTaskNode -> executeScriptTask(node, currentId)
                is UserTaskNode -> {
                    waitForUserTask(node)
                    nextSequence(currentId)
                }
                is ExclusiveGatewayNode -> routeExclusive(node)
                is EndNode -> return
                else -> return
            }
        }
    }

    private fun handleBusinessError(fromNodeId: String, errorCode: String?): String {
        if (errorCode == null) throw IllegalStateException("Business error without code")
        val t = def.transitions.firstOrNull {
            it.fromId == fromNodeId && it.errorCode == errorCode
        } ?: throw BpmnBusinessError(errorCode, "No boundary transition for errorCode=$errorCode at $fromNodeId")
        return t.toId
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

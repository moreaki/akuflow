package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.model.*
import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import com.convertic.akuflow.temporal.workflows.BpmnWorkflow
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/bpmn")
class BpmnDeploymentController(
    private val definitionService: BpmnDefinitionService
) {

    data class DeployRequest(val processKey: String, val xml: String)
    data class ConversionSummary(
        val nodesTotal: Int,
        val transitionsTotal: Int,
        val nodesByType: Map<String, Int>,
        val eventSubprocesses: Int,
        val conditionalTransitions: Int
    )

    data class TemporalHints(
        val workflowType: String,
        val taskQueue: String,
        val startCaseEndpoint: String,
        val startCaseProcessKey: String
    )

    data class DeployResponse(
        val processKey: String,
        val version: Int,
        val compiledProcessKey: String,
        val summary: ConversionSummary,
        val temporal: TemporalHints,
        val warnings: List<String> = emptyList()
    )

    @PostMapping("/deploy", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deploy(@RequestBody req: DeployRequest): DeployResponse {
        val result = definitionService.deploy(req.processKey, req.xml)
        val entity = result.entity
        val compiled = result.compiled

        val warnings = mutableListOf<String>()
        if (compiled.processKey != entity.processKey) {
            warnings += "Requested processKey '${entity.processKey}' does not match BPMN <process id> '${compiled.processKey}'."
        }

        return DeployResponse(
            processKey = entity.processKey,
            version = entity.version,
            compiledProcessKey = compiled.processKey,
            summary = buildSummary(compiled),
            temporal = TemporalHints(
                workflowType = BpmnWorkflow::class.java.name,
                taskQueue = "akuflow-bpmn",
                startCaseEndpoint = "/api/cases",
                startCaseProcessKey = entity.processKey
            ),
            warnings = warnings
        )
    }

    @GetMapping("/definitions", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listDefinitions(): List<BpmnDefinitionService.DefinitionSummary> =
        definitionService.listDefinitions()

    private fun buildSummary(def: CompiledProcess): ConversionSummary {
        val nodesByType = def.nodes.groupingBy { nodeTypeKey(it) }.eachCount()
        return ConversionSummary(
            nodesTotal = def.nodes.size,
            transitionsTotal = def.transitions.size,
            nodesByType = nodesByType.toSortedMap(),
            eventSubprocesses = def.eventSubprocesses.size,
            conditionalTransitions = def.transitions.count { it.conditionExpression != null }
        )
    }

    private fun nodeTypeKey(node: Node): String = when (node) {
        is StartNode -> "startEvent"
        is EndNode -> "endEvent"
        is UserTaskNode -> "userTask"
        is ServiceTaskNode -> "serviceTask"
        is ScriptTaskNode -> "scriptTask"
        is ExclusiveGatewayNode -> "exclusiveGateway"
        is ParallelGatewayNode -> "parallelGateway"
        is CallActivityNode -> "callActivity"
        is TimerBoundaryNode -> "timerBoundary"
    }
}

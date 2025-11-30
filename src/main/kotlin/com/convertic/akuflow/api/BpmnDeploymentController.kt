package com.convertic.akuflow.api

import com.convertic.akuflow.bpmn.runtime.BpmnDefinitionService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/bpmn")
class BpmnDeploymentController(
    private val definitionService: BpmnDefinitionService
) {

    data class DeployRequest(val processKey: String, val xml: String)
    data class DeployResponse(val processKey: String, val version: Int)

    @PostMapping("/deploy", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deploy(@RequestBody req: DeployRequest): DeployResponse {
        val entity = definitionService.deploy(req.processKey, req.xml)
        return DeployResponse(entity.processKey, entity.version)
    }
}

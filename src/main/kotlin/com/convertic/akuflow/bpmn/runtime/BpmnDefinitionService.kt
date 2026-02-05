package com.convertic.akuflow.bpmn.runtime

import com.convertic.akuflow.bpmn.compile.BpmnCompiler
import com.convertic.akuflow.bpmn.model.CompiledProcess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BpmnDefinitionService(
    private val repo: BpmnProcessDefinitionRepository,
    private val compiler: BpmnCompiler
) {

    data class DeployResult(
        val entity: BpmnProcessDefinitionEntity,
        val compiled: CompiledProcess
    )

    @Transactional
    fun deploy(processKey: String, xml: String): DeployResult {
        val latest = repo.findTopByProcessKeyOrderByVersionDesc(processKey)
        val nextVersion = (latest?.version ?: 0) + 1

        val compiled = compiler.compile(processKey, xml, nextVersion)
        val compiledJson = compiler.toJson(compiled)

        if (latest != null && latest.active) {
            latest.active = false
            repo.save(latest)
        }

        val entity = BpmnProcessDefinitionEntity(
            processKey = processKey,
            version = nextVersion,
            xml = xml,
            compiledJson = compiledJson,
            active = true
        )
        return DeployResult(repo.save(entity), compiled)
    }

    fun latestActive(processKey: String): CompiledProcess {
        val entity = repo.findTopByProcessKeyAndActiveIsTrueOrderByVersionDesc(processKey)
            ?: error("No active definition for processKey=$processKey")
        return compiler.fromJson(entity.compiledJson)
    }
}

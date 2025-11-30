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

    @Transactional
    fun deploy(processKey: String, xml: String): BpmnProcessDefinitionEntity {
        val latest = repo.findTopByProcessKeyOrderByVersionDesc(processKey)
        val nextVersion = (latest?.version ?: 0) + 1

        val compiled = compiler.compile(processKey, xml, nextVersion)
        val compiledJson = compiler.toJson(compiled)

        latest?.let {
            if (it.active) repo.save(it.copy(active = false))
        }

        val entity = BpmnProcessDefinitionEntity(
            processKey = processKey,
            version = nextVersion,
            xml = xml,
            compiledJson = compiledJson,
            active = true
        )
        return repo.save(entity)
    }

    fun latestActive(processKey: String): CompiledProcess {
        val entity = repo.findTopByProcessKeyAndActiveIsTrueOrderByVersionDesc(processKey)
            ?: error("No active definition for processKey=$processKey")
        return compiler.fromJson(entity.compiledJson)
    }
}

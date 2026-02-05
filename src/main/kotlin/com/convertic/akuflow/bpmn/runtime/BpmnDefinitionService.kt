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

    data class DefinitionSummary(
        val processKey: String,
        val version: Int,
        val active: Boolean,
        val deployedAt: java.time.Instant,
        val bpmnProcessId: String?,
        val processName: String?,
        val versionTag: String?,
        val modelerVersion: String?,
        val compiledProcessKey: String,
        val initialVars: Map<String, Any?> = emptyMap()
    )

    private data class BpmnMetadata(
        val bpmnProcessId: String?,
        val processName: String?,
        val versionTag: String?,
        val modelerVersion: String?
    )

    private val processIdRegex = Regex("(?s)<bpmn:process[^>]*\\bid=\"([^\"]+)\"")
    private val processNameRegex = Regex("(?s)<bpmn:process[^>]*\\bname=\"([^\"]*)\"")
    private val versionTagRegex = Regex("(?s)<bpmn:process[^>]*\\bcamunda:versionTag=\"([^\"]*)\"")
    private val modelerVersionRegex = Regex("(?s)<bpmn:definitions[^>]*\\bmodeler:executionPlatformVersion=\"([^\"]*)\"")

    @Transactional
    fun deploy(processKey: String, xml: String): DeployResult {
        val latest = repo.findTopByProcessKeyOrderByVersionDesc(processKey)
        val nextVersion = (latest?.version ?: 0) + 1

        val compiled = try {
            compiler.compile(processKey, xml, nextVersion)
        } catch (e: RuntimeException) {
            throw BpmnCompilationException(
                "Invalid BPMN for processKey=$processKey: ${e.message ?: "unknown error"}",
                e
            )
        }
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

    @Transactional(readOnly = true)
    fun listDefinitions(): List<DefinitionSummary> =
        repo.findAllByOrderByProcessKeyAscVersionDesc()
            .map { entity ->
                val meta = parseBpmnMetadata(entity.xml)
                DefinitionSummary(
                    processKey = entity.processKey,
                    version = entity.version,
                    active = entity.active,
                    deployedAt = entity.deployedAt,
                    bpmnProcessId = meta.bpmnProcessId,
                    processName = meta.processName ?: meta.bpmnProcessId,
                    versionTag = meta.versionTag,
                    modelerVersion = meta.modelerVersion,
                    compiledProcessKey = meta.bpmnProcessId ?: entity.processKey
                )
            }

    fun latestActive(processKey: String): CompiledProcess {
        val entity = repo.findTopByProcessKeyAndActiveIsTrueOrderByVersionDesc(processKey)
            ?: throw DefinitionNotFoundException("No active definition for processKey=$processKey")
        return loadCompiled(entity)
    }

    fun byVersion(processKey: String, version: Int): CompiledProcess {
        val entity = repo.findByProcessKeyAndVersion(processKey, version)
            ?: throw DefinitionNotFoundException("No definition for processKey=$processKey and version=$version")
        return loadCompiled(entity)
    }

    private fun parseBpmnMetadata(xml: String): BpmnMetadata =
        BpmnMetadata(
            bpmnProcessId = processIdRegex.find(xml)?.groupValues?.get(1),
            processName = processNameRegex.find(xml)?.groupValues?.get(1),
            versionTag = versionTagRegex.find(xml)?.groupValues?.get(1),
            modelerVersion = modelerVersionRegex.find(xml)?.groupValues?.get(1)
        )

    private fun loadCompiled(entity: BpmnProcessDefinitionEntity): CompiledProcess =
        try {
            compiler.fromJson(entity.compiledJson)
        } catch (e: RuntimeException) {
            compiler.compile(entity.processKey, entity.xml, entity.version)
        }
}

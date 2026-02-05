package com.convertic.akuflow.bpmn.runtime

import org.springframework.data.jpa.repository.JpaRepository

interface BpmnProcessDefinitionRepository :
    JpaRepository<BpmnProcessDefinitionEntity, Long> {

    fun findAllByOrderByProcessKeyAscVersionDesc(): List<BpmnProcessDefinitionEntity>

    fun findTopByProcessKeyAndActiveIsTrueOrderByVersionDesc(
        processKey: String
    ): BpmnProcessDefinitionEntity?

    fun findTopByProcessKeyOrderByVersionDesc(
        processKey: String
    ): BpmnProcessDefinitionEntity?

    fun findByProcessKeyAndVersion(
        processKey: String,
        version: Int
    ): BpmnProcessDefinitionEntity?
}

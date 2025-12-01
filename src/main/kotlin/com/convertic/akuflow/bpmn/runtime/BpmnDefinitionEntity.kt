package com.convertic.akuflow.bpmn.runtime

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "bpmn_process_definition",
    indexes = [
        Index(columnList = "processKey, version", unique = true),
        Index(columnList = "processKey, active")
    ]
)
class BpmnProcessDefinitionEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val processKey: String,

    val version: Int,

    @Lob
    @Column(columnDefinition = "TEXT")
    val xml: String,

    @Lob
    @Column(columnDefinition = "TEXT")
    val compiledJson: String,

    var active: Boolean = true,

    val deployedAt: Instant = Instant.now()
)

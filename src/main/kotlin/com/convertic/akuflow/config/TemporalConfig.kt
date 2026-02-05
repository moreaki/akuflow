package com.convertic.akuflow.config

import com.convertic.akuflow.temporal.activities.DefinitionLookupActivitiesImpl
import com.convertic.akuflow.temporal.activities.GroovyScriptActivitiesImpl
import com.convertic.akuflow.temporal.activities.ServiceTaskDispatcherActivitiesImpl
import com.convertic.akuflow.temporal.workflows.BpmnWorkflowImpl
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TemporalConfig {

    @Bean
    fun workflowServiceStubs(): WorkflowServiceStubs =
        // runs a local in-process Temporal service (same effect as `target: local`)
        WorkflowServiceStubs.newLocalServiceStubs()

    @Bean
    fun workflowClient(serviceStubs: WorkflowServiceStubs): WorkflowClient =
        WorkflowClient.newInstance(serviceStubs)

    @Bean(destroyMethod = "shutdown")
    fun workerFactory(client: WorkflowClient): WorkerFactory =
        WorkerFactory.newInstance(client)

    /**
     * Create and start a worker on task queue "akuflow-bpmn"
     * registering BpmnWorkflowImpl + activities.
     *
     * Bean is started when the context is ready, and shut down on close.
     */
    @Bean(initMethod = "start", destroyMethod = "")
    fun akuflowBpmnWorker(
        workerFactory: WorkerFactory,
        serviceTaskDispatcherActivitiesImpl: ServiceTaskDispatcherActivitiesImpl,
        groovyScriptActivitiesImpl: GroovyScriptActivitiesImpl,
        definitionLookupActivitiesImpl: DefinitionLookupActivitiesImpl
    ): Worker {
        val worker = workerFactory.newWorker("akuflow-bpmn")

        worker.registerWorkflowImplementationTypes(BpmnWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(
            serviceTaskDispatcherActivitiesImpl,
            groovyScriptActivitiesImpl,
            definitionLookupActivitiesImpl
        )

        return worker
    }
}

package com.convertic.akuflow.temporal.activities

import com.convertic.akuflow.bpmn.model.CompiledProcess
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface DefinitionLookupActivities {
    @ActivityMethod
    fun loadCompiledProcess(processKey: String): CompiledProcess
}

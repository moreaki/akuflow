package com.convertic.akuflow.temporal.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface GroovyScriptActivities {
    @ActivityMethod
    fun executeGroovy(
        script: String,
        vars: MutableMap<String, Any?>,
        handlerKey: String
    ): MutableMap<String, Any?>
}

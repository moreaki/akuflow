package com.convertic.akuflow.temporal.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface ServiceTaskDispatcherActivities {
    @ActivityMethod
    fun execute(handlerKey: String, vars: MutableMap<String, Any?>): MutableMap<String, Any?>
}

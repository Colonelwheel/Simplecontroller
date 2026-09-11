package com.example.simplecontroller.ui

import android.os.Handler
import android.os.Looper

/** Main-thread scheduler adapter for [ButtonAimPayloadExecutor]. */
class HandlerButtonAimPayloadScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : ButtonAimPayloadScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): ButtonAimScheduledTask {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
        return ButtonAimScheduledTask { handler.removeCallbacks(runnable) }
    }
}

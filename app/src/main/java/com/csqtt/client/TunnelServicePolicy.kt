package com.csqtt.client

internal object TunnelServicePolicy {
    const val STARTUP_GRACE_MS = 60_000L

    fun shouldStop(
        wasEverRunning: Boolean,
        running: Boolean,
        elapsedMs: Long,
    ): Boolean = !running && (wasEverRunning || elapsedMs >= STARTUP_GRACE_MS)
}

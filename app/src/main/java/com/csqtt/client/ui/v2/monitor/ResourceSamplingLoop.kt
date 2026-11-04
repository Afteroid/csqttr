package com.csqtt.client.ui.v2.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** No polling while idle. Multiple visible consumers share a single sampler. */
internal class ResourceSamplingLoop(
    scope: CoroutineScope,
    private val onResume: () -> Unit,
    private val sample: () -> Unit,
    private val intervalMillis: Long = 800,
) {
    private val lock = Any()
    private val required = MutableStateFlow(false)
    private var viewers = 0
    private var measuring = false

    val hasVisibleConsumers: Boolean get() = synchronized(lock) { viewers > 0 }

    init {
        scope.launch {
            required.collectLatest { active ->
                if (active) {
                    onResume()
                    while (currentCoroutineContext().isActive) {
                        sample()
                        delay(intervalMillis)
                    }
                }
            }
        }
    }

    fun acquireVisibleConsumer(): AutoCloseable {
        synchronized(lock) {
            viewers++
            required.value = true
        }
        var released = false
        return AutoCloseable {
            synchronized(lock) {
                if (!released) {
                    released = true
                    viewers--
                    required.value = viewers > 0 || measuring
                }
            }
        }
    }

    fun setMeasurementActive(active: Boolean) {
        synchronized(lock) {
            measuring = active
            required.value = viewers > 0 || measuring
        }
    }
}

package com.csqtt.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ProfilePingState(val running: Boolean = false, val profile: Int? = null, val stopping: Boolean = false)

/** Observable batch state; Job.isActive alone cannot invalidate a Compose screen. */
internal class ProfilePingController(
    private val scope: CoroutineScope,
    private val probe: suspend (Int) -> ProfilePingResult,
    private val clear: (Int) -> Unit,
    private val save: (Int, ProfilePingResult) -> Unit,
) {
    private var job: Job? = null
    private val mutableState = MutableStateFlow(ProfilePingState())
    val state = mutableState.asStateFlow()

    fun start(profiles: List<Int>) {
        if (job != null || profiles.isEmpty()) return
        mutableState.value = ProfilePingState(running = true)
        val batch = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (id in profiles.distinct()) {
                    ensureActive()
                    mutableState.value = ProfilePingState(running = true, profile = id)
                    clear(id)
                    val result = try { probe(id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { ProfilePingResult.Failure("Не удалось проверить туннель") }
                    ensureActive() // A cancelled blocking operation must not publish a stale result.
                    save(id, result)
                }
            } finally {
                job = null
                mutableState.value = ProfilePingState()
            }
        }
        job = batch
        batch.invokeOnCompletion {
            // A scope cancelled before the lazy body starts must also clear the indicator.
            if (job === batch) {
                job = null
                mutableState.value = ProfilePingState()
            }
        }
        batch.start()
    }

    fun cancel() {
        val active = job ?: return
        mutableState.value = mutableState.value.copy(stopping = true)
        active.cancel()
    }
}

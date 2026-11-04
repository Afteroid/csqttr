package com.csqtt.client

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePingControllerTest {
    @Test fun completionClearsObservableStateAndAllowsAnotherRun() = runTest {
        val saved = mutableListOf<Int>()
        val controller = ProfilePingController(this, { ProfilePingResult.Success(30) }, {}, { id, _ -> saved.add(id) })
        controller.start(listOf(1, 2))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), saved)
        assertEquals(ProfilePingState(), controller.state.value)
        controller.start(listOf(1)); advanceUntilIdle()
        assertEquals(listOf(1, 2, 1), saved)
    }

    @Test fun cancelStopsBatchWithoutPublishingOrStartingNextProfile() = runTest {
        val visited = mutableListOf<Int>(); val saved = mutableListOf<Int>()
        val controller = ProfilePingController(this, { id -> visited.add(id); delay(60_000); ProfilePingResult.Success(1) }, {}, { id, _ -> saved.add(id) })
        controller.start(listOf(1, 2)); runCurrent()
        controller.cancel(); advanceUntilIdle()
        assertEquals(listOf(1), visited)
        assertTrue(saved.isEmpty())
        assertEquals(ProfilePingState(), controller.state.value)
    }

    @Test fun cancellationKeepsStoppingUntilCleanupCompletesAndBlocksSecondBatch() = runTest {
        val gate = CompletableDeferred<Unit>(); val visited = mutableListOf<Int>()
        val controller = ProfilePingController(this, { id ->
            visited.add(id)
            try { awaitCancellation() } finally { withContext(NonCancellable) { gate.await() } }
        }, {}, { _, _ -> fail("Cancelled result") })
        controller.start(listOf(1, 2)); runCurrent(); controller.cancel(); runCurrent()
        assertTrue(controller.state.value.stopping)
        controller.start(listOf(3)); runCurrent(); assertEquals(listOf(1), visited)
        gate.complete(Unit); advanceUntilIdle(); assertFalse(controller.state.value.running)
    }

    @Test fun cancelledScopeDoesNotLeaveSpinnerWhenBodyNeverStarts() = runTest {
        val scope = CoroutineScope(coroutineContext + Job().apply { cancel() })
        val controller = ProfilePingController(scope, { ProfilePingResult.Success(1) }, {}, { _, _ -> })
        controller.start(listOf(1)); advanceUntilIdle()
        assertFalse(controller.state.value.running)
    }

    @Test fun resultReturningFromNonCancellableWorkIsDiscardedAfterStop() = runTest {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        val controller = ProfilePingController(this, {
            withContext(NonCancellable) { gate.await(); ProfilePingResult.Success(60) }
        }, {}, { id, _ -> saved.add(id) })
        controller.start(listOf(1, 2)); runCurrent()
        controller.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertTrue(saved.isEmpty())
        assertFalse(controller.state.value.running)
    }
}

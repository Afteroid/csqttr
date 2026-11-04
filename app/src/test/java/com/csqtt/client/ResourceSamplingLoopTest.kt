package com.csqtt.client

import com.csqtt.client.ui.v2.monitor.ResourceSamplingLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceSamplingLoopTest {
    @Test fun idleMonitorDoesNotPoll() = runTest {
        var samples = 0
        ResourceSamplingLoop(backgroundScope, {}, { samples++ })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, samples)
    }

    @Test fun closingLastViewerStopsPollingAndReopenResetsBaseline() = runTest {
        var samples = 0
        var starts = 0
        val loop = ResourceSamplingLoop(backgroundScope, { starts++ }, { samples++ })
        val viewer = loop.acquireVisibleConsumer()
        runCurrent()
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(3, samples)
        viewer.close()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(3, samples)
        val reopened = loop.acquireVisibleConsumer()
        runCurrent()
        assertEquals(2, starts)
        assertEquals(4, samples)
        reopened.close()
    }

    @Test fun sheetAndHudShareOneLoopAndReleaseIndependently() = runTest {
        var samples = 0
        val loop = ResourceSamplingLoop(backgroundScope, {}, { samples++ })
        val sheet = loop.acquireVisibleConsumer()
        val hud = loop.acquireVisibleConsumer()
        runCurrent()
        assertEquals(1, samples)
        sheet.close()
        sheet.close() // Releasing twice must not cancel the HUD's lease.
        advanceTimeBy(800)
        runCurrent()
        assertEquals(2, samples)
        hud.close()
        runCurrent()
        assertFalse(loop.hasVisibleConsumers)
        advanceTimeBy(8_000)
        assertEquals(2, samples)
    }

    @Test fun explicitMeasurementContinuesWithoutUiUntilStopped() = runTest {
        var samples = 0
        val loop = ResourceSamplingLoop(backgroundScope, {}, { samples++ })
        val viewer = loop.acquireVisibleConsumer()
        loop.setMeasurementActive(true)
        runCurrent()
        viewer.close()
        advanceTimeBy(800)
        runCurrent()
        assertEquals(2, samples)
        assertFalse(loop.hasVisibleConsumers)
        loop.setMeasurementActive(false)
        runCurrent()
        advanceTimeBy(8_000)
        assertEquals(2, samples)
    }

    @Test fun stoppingMeasurementKeepsVisibleMonitorAlive() = runTest {
        var samples = 0
        val loop = ResourceSamplingLoop(backgroundScope, {}, { samples++ })
        loop.setMeasurementActive(true)
        runCurrent()
        val viewer = loop.acquireVisibleConsumer()
        loop.setMeasurementActive(false)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(2, samples)
        viewer.close()
    }

    @Test fun measurementCanStartWithoutEverOpeningMonitor() = runTest {
        var samples = 0
        val loop = ResourceSamplingLoop(backgroundScope, {}, { samples++ })
        loop.setMeasurementActive(true)
        runCurrent()
        assertEquals(1, samples)
        loop.setMeasurementActive(false)
        runCurrent()
        advanceTimeBy(8_000)
        assertEquals(1, samples)
    }
}

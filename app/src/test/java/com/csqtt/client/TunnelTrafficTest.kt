package com.csqtt.client

import org.junit.Assert.*
import org.junit.Test

class TunnelTrafficTest {
    @Test fun ratesUseElapsedTimeAndKeepDirectionsSeparate() {
        val tracker = TunnelTrafficTracker()
        tracker.beginProcess(1, 1000)
        tracker.sample(1, 100, 400, 1500)
        assertEquals(0.0, tracker.state.value.sendBytesPerSecond, 0.0)
        assertEquals(100L, tracker.state.value.sentBytes)
        tracker.sample(1, 200, 800, 2000)
        assertEquals(200.0, tracker.state.value.sendBytesPerSecond, 0.0)
        assertEquals(800.0, tracker.state.value.receiveBytesPerSecond, 0.0)
        tracker.sample(1, 600, 1600, 4000)
        assertEquals(200.0, tracker.state.value.sendBytesPerSecond, 0.0)
        assertEquals(400.0, tracker.state.value.receiveBytesPerSecond, 0.0)
        assertEquals(600L, tracker.state.value.sentBytes)
        assertEquals(1600L, tracker.state.value.receivedBytes)
    }

    @Test fun queuedEventsDoNotCreateMillisecondSpeedSpikes() {
        val tracker = TunnelTrafficTracker()
        tracker.beginProcess(1, 0)
        tracker.sample(1, 1000, 2000, 1000)
        tracker.sample(1, 2000, 4000, 1001)
        tracker.sample(1, 3000, 6000, 1002)
        assertEquals(1000.0, tracker.state.value.sendBytesPerSecond, 0.0)
        assertEquals(3000L, tracker.state.value.sentBytes)
        tracker.sample(1, 4000, 8000, 2500)
        assertEquals(2000.0, tracker.state.value.sendBytesPerSecond, 0.0)
        assertEquals(4000.0, tracker.state.value.receiveBytesPerSecond, 0.0)
    }

    @Test fun restartPreservesSessionTotalsAndIgnoresOldReader() {
        val tracker = TunnelTrafficTracker()
        tracker.beginProcess(1, 0)
        tracker.sample(1, 10, 20, 1000)
        tracker.endProcess(1)
        tracker.beginProcess(2, 2000)
        tracker.sample(1, 9999, 9999, 2500)
        tracker.endProcess(1)
        tracker.sample(2, 30, 40, 3000)
        assertEquals(40L, tracker.state.value.sentBytes)
        assertEquals(60L, tracker.state.value.receivedBytes)
        assertEquals(30.0, tracker.state.value.sendBytesPerSecond, 0.0)
        tracker.reset()
        assertEquals(TunnelTraffic(), tracker.state.value)
    }

    @Test fun staleIdleAndStoppedSamplesDoNotKeepOldSpeed() {
        val tracker = TunnelTrafficTracker()
        tracker.beginProcess(1, 0)
        tracker.sample(1, 100, 200, 1000)
        tracker.expire(6001)
        assertEquals(0.0, tracker.state.value.receiveBytesPerSecond, 0.0)
        assertEquals(200L, tracker.state.value.receivedBytes)
        tracker.sample(1, 200, 300, 7000)
        tracker.sample(1, 200, 300, 10000)
        assertEquals(0.0, tracker.state.value.receiveBytesPerSecond, 0.0)
        tracker.endProcess(1)
        tracker.sample(1, 999, 999, 11000)
        assertEquals(300L, tracker.state.value.receivedBytes)
    }

    @Test fun duplicateTimestampAndCounterResetDoNotCreateSpikes() {
        val tracker = TunnelTrafficTracker()
        tracker.beginProcess(1, 0)
        tracker.sample(1, 1000, 1000, 1000)
        tracker.sample(1, 2000, 2000, 1000)
        assertEquals(1000L, tracker.state.value.sentBytes)
        tracker.sample(1, 5, 10, 2000)
        assertEquals(1005L, tracker.state.value.sentBytes)
        assertEquals(0.0, tracker.state.value.sendBytesPerSecond, 0.0)
    }

    @Test fun uiAndNotificationUseSameUnitsAndDirections() {
        val traffic = TunnelTraffic(11_618_222, 8_095_007, 437.0, 361.0, 1000)
        assertEquals("↓ 437 Б/с   ↑ 361 Б/с", TunnelTrafficText.speed(traffic))
        assertTrue(TunnelTrafficText.total(traffic).startsWith("Принято 11,08 МБ · Отправлено 7,72 МБ"))
        assertTrue(TunnelTrafficText.notification(traffic, 12).startsWith(TunnelTrafficText.speed(traffic)))
        assertTrue(TunnelTrafficText.notification(traffic, 12).contains(TunnelTrafficText.total(traffic)))
        assertEquals("Подключен • Потоков: 12", TunnelTrafficText.notification(traffic, 12).lines().last())
        assertEquals("↓ —   ↑ —", TunnelTrafficText.speed(TunnelTraffic()))
    }
}

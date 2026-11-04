package com.csqtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

data class TunnelTraffic(
    val receivedBytes: Long = 0,
    val sentBytes: Long = 0,
    val receiveBytesPerSecond: Double = 0.0,
    val sendBytesPerSecond: Double = 0.0,
    val sampledAtMs: Long? = null,
)

/**
 * Consumes existing STATS events. No polling, timers or network activity.
 * Native counters measure inner IP bytes: upload before dispatch, download after local delivery.
 * Rates are interval averages at event receipt, not acknowledged remote throughput or wire usage.
 */
internal class TunnelTrafficTracker {
    val state = MutableStateFlow(TunnelTraffic())
    private var generation: Long? = null
    private var lastAtMs = 0L
    private var lastUp = 0L
    private var lastDown = 0L
    private var rateAtMs = 0L
    private var rateUp = 0L
    private var rateDown = 0L

    @Synchronized fun reset() {
        generation = null
        state.value = TunnelTraffic()
    }

    @Synchronized fun beginProcess(id: Long, nowMs: Long) {
        generation = id
        lastAtMs = nowMs
        lastUp = 0
        lastDown = 0
        rateAtMs = nowMs
        rateUp = 0
        rateDown = 0
        state.value = state.value.copy(receiveBytesPerSecond = 0.0, sendBytesPerSecond = 0.0, sampledAtMs = null)
    }

    @Synchronized fun sample(id: Long, bytesUp: Long, bytesDown: Long, nowMs: Long) {
        if (id != generation || nowMs <= lastAtMs) return
        val up = bytesUp.coerceAtLeast(0)
        val down = bytesDown.coerceAtLeast(0)
        val restarted = up < lastUp || down < lastDown
        val addedUp = if (up < lastUp) up else up - lastUp
        val addedDown = if (down < lastDown) down else down - lastDown
        val old = state.value
        // Coalesce closely spaced events: a queued reader must not divide a whole STATS
        // interval's bytes by a few milliseconds. Keep totals current on every event.
        val updateRate = !restarted && nowMs - rateAtMs >= 1_000L
        val seconds = (nowMs - rateAtMs) / 1000.0
        state.value = TunnelTraffic(
            receivedBytes = saturatedAdd(old.receivedBytes, addedDown),
            sentBytes = saturatedAdd(old.sentBytes, addedUp),
            receiveBytesPerSecond = when {
                restarted -> 0.0
                updateRate -> (down - rateDown) / seconds
                else -> old.receiveBytesPerSecond
            },
            sendBytesPerSecond = when {
                restarted -> 0.0
                updateRate -> (up - rateUp) / seconds
                else -> old.sendBytesPerSecond
            },
            sampledAtMs = nowMs,
        )
        lastUp = up
        lastDown = down
        lastAtMs = nowMs
        if (updateRate || restarted) {
            rateAtMs = nowMs
            rateUp = up
            rateDown = down
        }
    }

    @Synchronized fun expire(nowMs: Long) {
        val snapshot = state.value
        if (snapshot.sampledAtMs?.let { nowMs - it > 5_000L } == true) {
            state.value = snapshot.copy(receiveBytesPerSecond = 0.0, sendBytesPerSecond = 0.0)
        }
    }

    @Synchronized fun endProcess(id: Long?) {
        if (id != null && id != generation) return
        generation = null
        state.value = state.value.copy(receiveBytesPerSecond = 0.0, sendBytesPerSecond = 0.0)
    }

    private fun saturatedAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
}

internal object TunnelTrafficText {
    const val CONNECTED_STATUS_PREFIX = "Подключен • Потоков: "
    private val locale = Locale.forLanguageTag("ru-RU")
    private fun amount(bytes: Double): String {
        var value = bytes.coerceAtLeast(0.0)
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ", "ЭБ")
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return if (unit == 0) "${value.toLong()} ${units[unit]}"
        else String.format(locale, "%.2f %s", value, units[unit])
    }

    fun speed(value: TunnelTraffic): String = if (value.sampledAtMs == null) "↓ —   ↑ —" else
        "↓ ${amount(value.receiveBytesPerSecond)}/с   ↑ ${amount(value.sendBytesPerSecond)}/с"

    fun total(value: TunnelTraffic): String =
        "Принято ${amount(value.receivedBytes.toDouble())} · Отправлено ${amount(value.sentBytes.toDouble())}"

    fun notification(value: TunnelTraffic, workers: Int): String =
        "${speed(value)}\n${total(value)}\n$CONNECTED_STATUS_PREFIX$workers"
}

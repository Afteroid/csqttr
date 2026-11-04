package com.csqtt.client

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local native diagnostics, never a command to the tunnel. */
internal object StreamDiagnosticParser {
    const val PREFIX = "__CSQTT_STREAM__|"
    const val KEY_PREFIX = "stream_diag_"
    const val MAX_ENTRIES = 1024

    fun parse(line: String): LogEntry? {
        if (!line.startsWith(PREFIX) || line.length > 4096) return null
        return runCatching {
            val json = JSONObject(line.removePrefix(PREFIX))
            val worker = json.getInt("worker")
            val attempt = json.getLong("attempt")
            val sequence = json.getLong("sequence")
            val time = json.getLong("time_ms")
            val elapsed = json.getLong("elapsed_ms")
            require(worker in 1..CsqttConstants.Tunnel.MAX_WORKERS && attempt > 0 && sequence > 0 && time > 0 && elapsed >= 0)
            val turn = json.getString("turn").take(256)
            val transport = json.getString("transport").take(16)
            val stage = json.getString("stage").take(32)
            val status = json.getString("status").take(24)
            require(turn.isNotBlank() && stage.isNotBlank() && status.isNotBlank())
            val reason = NativeTunnelLogParser.redact(json.optString("reason", "")).take(512)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT).format(Date(time))
            LogEntry(
                key = "$KEY_PREFIX${time.toString().padStart(16, '0')}_${sequence.toString().padStart(20, '0')}_${worker}_$attempt",
                message = "[ПОТОК #$worker] $stamp · ID подключения: $attempt\nTURN=$turn · $transport\n$stage: $status · ${elapsed} мс" +
                    if (reason.isBlank()) "" else "\n$reason",
                priority = 65,
                // Existing error summaries remain in Normal; per-attempt detail is opt-in to view.
                level = LogLevel.DBG,
            )
        }.getOrNull()
    }

    fun evictionIndex(entries: List<LogEntry>, ordinaryLimit: Int): Int {
        // Keep a separate 15-minute traffic history; periodic samples must not evict handshake evidence.
        if (entries.count(::isTraffic) > 180)
            return entries.indexOfFirst(::isTraffic)
        if (entries.count { isDetailed(it) && !isTraffic(it) } > MAX_ENTRIES)
            return entries.indexOfFirst { isDetailed(it) && !isTraffic(it) }
        if (entries.count { !isDetailed(it) } > ordinaryLimit)
            return entries.indexOfFirst { !isDetailed(it) }
        return -1
    }

    private fun isTraffic(entry: LogEntry): Boolean = entry.key.startsWith("traffic_diag_")

    private fun isDetailed(entry: LogEntry): Boolean = !entry.isError &&
        (entry.level == LogLevel.DBG || entry.level == LogLevel.TURN || entry.level == LogLevel.PING)
}

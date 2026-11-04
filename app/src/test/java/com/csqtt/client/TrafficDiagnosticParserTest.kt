package com.csqtt.client

import com.csqtt.client.ui.v2.screens.logs.filterLogEntry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrafficDiagnosticParserTest {
    private fun payload(): JSONObject {
        val names = "dispatch_bytes no_worker_drops wrap_drops fragment_pool_drops udp_socket_bytes udp_socket_packets udp_send_drops udp_closing_drops udp_would_block udp_wait_us udp_wait_max_us turn_enqueue_errors turn_queue_full turn_queue_wait_us turn_queue_wait_max_us turn_closing_drops relay_bytes relay_packets relay_queue_drops relay_pool_drops auth_drops replay_drops non_rtp_drops non_ip_drops downlink_queue_drops tun_write_drops tun_would_block tun_transient_errors"
        val counters = JSONObject()
        names.split(' ').forEach { counters.put(it, 0) }
        return JSONObject().put("sequence", 1).put("time_ms", 1793730000123L).put("elapsed_ms", 5200)
            .put("active", 70).put("app_up_bytes", 6500000).put("app_down_bytes", 3250000).put("counters", counters)
    }
    private fun parse(j: JSONObject) = TrafficDiagnosticParser.parse(TrafficDiagnosticParser.PREFIX + j)
    @Test fun ratesUseRealWindowAndSnapshotsSurviveDetailedCopyWithoutMerging() {
        val j = payload()
        val entry = requireNotNull(parse(j))
        assertTrue(entry.message.contains("↑10.00 ↓5.00 Мбит/с"))
        assertTrue(entry.message.contains("потери сети неизвестны"))
        assertTrue(entry.message.length < 4096)
        assertEquals(entry, filterLogEntry(entry, "Подробные", "Туннель"))
        assertNull(filterLogEntry(entry, "Обычные", "Все события"))
        assertNotEquals(entry.key, requireNotNull(parse(j.put("sequence", 2))).key)
    }
    @Test fun malformedOrNegativeCountersCannotCreateFalseRates() {
        assertNull(parse(payload().put("elapsed_ms", 0)))
        assertNull(parse(payload().put("app_up_bytes", -1)))
        val j = payload()
        j.getJSONObject("counters").put("auth_drops", -1)
        assertNull(parse(j))
        j.getJSONObject("counters").remove("auth_drops")
        assertNull(parse(j))
    }
    @Test fun trafficHistoryDoesNotEraseHandshakeEvidence() {
        val handshake = LogEntry("stream_diag_saved", "timeout", priority = 65, level = LogLevel.DBG)
        val samples = List(181) { requireNotNull(parse(payload().put("sequence", it + 1))) }
        val entries = listOf(handshake) + samples
        assertEquals(1, StreamDiagnosticParser.evictionIndex(entries, 100))
        assertEquals(-1, StreamDiagnosticParser.evictionIndex(entries.dropLast(1), 100))
    }
}

package com.csqtt.client

import com.csqtt.client.ui.v2.screens.logs.filterLogEntry
import org.junit.Assert.*
import org.junit.Test

class StreamDiagnosticParserTest {
    private fun event(worker: Int = 7, attempt: Int = 1, seq: Int = 1, turn: String = "91.231.135.130:19302", reason: String = "inbound=0; retransmits=16") =
        "${StreamDiagnosticParser.PREFIX}" + """{"worker":$worker,"attempt":$attempt,"sequence":$seq,"time_ms":1793730000123,"elapsed_ms":8000,"turn":"$turn","transport":"UDP","stage":"dtls","status":"timeout","reason":"$reason"}"""

    @Test fun timeoutDetailsReachDetailedLogsAndCopiedMessage() {
        val entry = requireNotNull(StreamDiagnosticParser.parse(event()))
        assertTrue(entry.message.contains("ПОТОК #7"))
        assertTrue(entry.message.contains("TURN=91.231.135.130:19302"))
        assertTrue(entry.message.contains("8000 мс"))
        assertTrue(entry.message.contains("inbound=0; retransmits=16"))
        assertTrue(entry.message.contains("2026-11-03"))
        assertEquals(entry, filterLogEntry(entry, "Подробные", "Туннель"))
        assertNull(filterLogEntry(entry, "Обычные", "Все события"))
    }
    @Test fun workersRetriesAndRepeatedEventsRemainSeparate() {
        val entries = listOf(event(), event(worker=8), event(attempt=2), event(seq=2))
            .map { requireNotNull(StreamDiagnosticParser.parse(it)) }
        assertEquals(4, entries.map { it.key }.toSet().size)
        assertTrue(entries[0].key < entries[3].key)
    }
    @Test fun malformedEventsAreIgnoredAndSecretsRedacted() {
        listOf("", "${StreamDiagnosticParser.PREFIX}{}", event(worker=0), event(attempt=0), event(seq=0),
            event().replace("8000", "-1")).forEach { assertNull(StreamDiagnosticParser.parse(it)) }
        val entry = requireNotNull(StreamDiagnosticParser.parse(event(reason="password=secret token=hidden")))
        assertFalse(entry.message.contains("secret"))
        assertFalse(entry.message.contains("hidden"))
    }
    @Test fun diagnosticHistoryIsBoundedWithoutEvictingNormalStatus() {
        val ordinary = LogEntry("ready", "Ready")
        val entries = mutableListOf(ordinary)
        repeat(StreamDiagnosticParser.MAX_ENTRIES + 1) { entries.add(requireNotNull(StreamDiagnosticParser.parse(event(seq=it+1)))) }
        assertEquals(1, StreamDiagnosticParser.evictionIndex(entries, 100))
        entries.removeAt(1)
        assertEquals(-1, StreamDiagnosticParser.evictionIndex(entries, 100))
        assertEquals(ordinary, entries.first())
        assertEquals(0, StreamDiagnosticParser.evictionIndex(listOf(ordinary, ordinary.copy(key="stats")), 1))
    }
}

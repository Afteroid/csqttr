package com.csqtt.client

import com.csqtt.client.ui.v2.screens.logs.filterLogEntry
import org.junit.Assert.*
import org.junit.Test

class NativeTunnelLogParserTest {
    @Test fun detailedShowsHandshakeTurnMaintenanceAndRetries() {
        val lines = listOf(
            "[TURN] Подключение к 192.0.2.1:3478",
            "[TURN] Аллокация активна: relay 192.0.2.2:40000",
            "[TURN] ChannelBind активен: канал 0x4000",
            "[TURN] Refresh аллокации подтверждён ✓",
            "[СЕССИЯ #1] DTLS: старт рукопожатия WebRTC (ClientHello 123 байт)",
            "[СЕССИЯ #1] DTLS: повтор последнего полёта #2 (123 байт, состояние 1)",
            "[TURN][RETRY] Квота Allocate у креда #1; STUN error 486",
            "[ВОРКЕР #2] [СЕТЬ][RETRY] Временная ошибка сокета",
            "[ВОРКЕР #2] Конфиг получен",
        )
        lines.forEach { line ->
            val entry = requireNotNull(NativeTunnelLogParser.parse(line)) { line }
            assertEquals(line, entry.message)
            assertNull(line, filterLogEntry(entry, "Обычные", "Все события"))
            assertNotNull(line, filterLogEntry(entry, "Подробные", "Все события"))
            assertFalse(line, entry.isError)
        }
    }

    @Test fun normalKeepsTransportDiagnosticsAndRealFailures() {
        listOf(
            "[TURN] Транспорт: udp",
            "[КЛИЕНТ] TURN: udp | SRTP: auto | DTLS: 1.2",
            "[СЕССИЯ #1] SRTP: AUTO fallback -> WRAP-SRTP (legacy ключи)",
            "[ДИАГНОСТИКА] TURN-креды получены, но ни один allocation/handshake с сервером не завершился",
            "[TURN][DOWN] Permission истекает, переподнимаем поток",
            "[СЕССИЯ #1] DTLS: таймаут рукопожатия за 8000 мс",
        ).forEach { line ->
            val entry = requireNotNull(NativeTunnelLogParser.parse(line))
            assertNotNull(line, filterLogEntry(entry, "Обычные", "Все события"))
            assertNotNull(line, filterLogEntry(entry, "Подробные", "Все события"))
        }
        assertTrue(requireNotNull(NativeTunnelLogParser.parse("[WRAP] Ошибка ключа")).isError)
    }

    @Test fun differentWorkersAreNeverMergedButCountersWithinAWorkerAre() {
        val a = requireNotNull(NativeTunnelLogParser.parse("[СЕССИЯ #1] DTLS: повтор последнего полёта #2 (123 байт, состояние 1)"))
        val b = requireNotNull(NativeTunnelLogParser.parse("[СЕССИЯ #70] DTLS: повтор последнего полёта #6 (456 байт, состояние 3)"))
        assertNotEquals(a.key, b.key)
        assertNotEquals(a.message, b.message)
        val c = requireNotNull(NativeTunnelLogParser.parse("[СЕССИЯ #1] DTLS: повтор последнего полёта #6 (456 байт, состояние 3)"))
        assertEquals(a.key, c.key)
        assertNotEquals(NativeTunnelLogParser.parse("[TURN] Подключение к turn:192.0.2.1:19302")?.key,
            NativeTunnelLogParser.parse("[TURN] Подключение к turn:192.0.2.2:19302")?.key)
    }

    @Test fun controlsAndSecretsAreNotExposed() {
        listOf("CAPTCHA_SOLVE|auto|url|secret", "[СЕССИЯ] TUNCONF:secret", "[ВОРКЕР] GETCONF: FATAL_AUTH: secret",
            "[КЛИЕНТ] Хешей: sensitive", "[READY] Поток готов").forEach {
            assertNull(NativeTunnelLogParser.parse(it))
        }
        val text = NativeTunnelLogParser.redact("[TURN] token=private password=hidden access_token=secret https://vk.ru/call/join/hiddenHash")
        listOf("private", "hidden", "secret", "hiddenHash").forEach { assertFalse(text.contains(it)) }
        assertEquals("[GETCONF] Обмен конфигурацией с сервером",
            NativeTunnelLogParser.parse("[ВОРКЕР #2] GETCONF payload: secret")?.message)
    }

    @Test fun stunCodesAreNotMatchedInsideAddressesOrCounters() {
        assertFalse(TunnelLogPolicy.isInternalRecovery("[TURN] Refresh 1437, relay port 48600"))
        assertTrue(TunnelLogPolicy.isInternalRecovery("[TURN] STUN error 437"))
        assertFalse(TunnelLogPolicy.isInternalRecovery("[TURN][DOWN] Refresh timeout, поток остановлен"))
    }
}

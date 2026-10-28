package com.csqtt.client

import com.csqtt.client.ui.v2.screens.logs.filterLogEntry
import org.junit.Assert.*
import org.junit.Test

class LogFiltersTest {
    @Test fun severityFilteringTreatsErrorFlagAsAnErrorAndKeepsTheMessage() {
        val error = LogEntry("deploy_test", "[ДЕПЛОЙ] Ошибка SSH", isError = true, level = LogLevel.LOG)
        assertEquals(error, filterLogEntry(error, "Ошибки", "Все события"))
        assertNull(filterLogEntry(error, "Успех", "Все события"))
        assertNotNull(filterLogEntry(LogEntry("test", "[OK] Готово", level = LogLevel.OK), "Успех", "Все события"))
    }
    @Test fun sourceAndLevelFiltersCombine() {
        val deploy = LogEntry("deploy_step", "SSH подключён", level = LogLevel.NET)
        assertNotNull(filterLogEntry(deploy, "Сеть", "Деплой"))
        assertNull(filterLogEntry(deploy, "Сеть", "Туннель"))
        assertNull(filterLogEntry(deploy, "Ошибки", "Деплой"))
        assertNotNull(filterLogEntry(LogEntry("update_check", "Обновлений нет"), "Все уровни", "Система"))
    }
    @Test fun separatorsAreRemovedWithoutDroppingRealLogContent() {
        assertNull(filterLogEntry(LogEntry("line", "[LOG] ──────────"), "Все уровни", "Все события"))
        assertEquals("SSH: порт 5052", filterLogEntry(LogEntry("text", "────\nSSH: порт 5052\n===="), "Все уровни", "Все события")?.message)
        assertNotNull(filterLogEntry(LogEntry("text", "Неверный пароль: a-b_c"), "Все уровни", "Все события"))
    }
}

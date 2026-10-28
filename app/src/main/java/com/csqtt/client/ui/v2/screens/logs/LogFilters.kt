package com.csqtt.client.ui.v2.screens.logs

import com.csqtt.client.LogEntry
import com.csqtt.client.LogLevel

internal fun effectiveLogLevel(entry: LogEntry): LogLevel = when {
    entry.isError -> LogLevel.ERR
    entry.level != null -> entry.level
    entry.priority <= 2 -> LogLevel.OK
    entry.priority <= 4 -> LogLevel.DBG
    entry.priority <= 6 -> LogLevel.NET
    else -> LogLevel.LOG
}

internal val logLevels = listOf("Все уровни", "Ошибки", "Успех", "Сеть", "TURN", "Пинг", "Отладка", "Сообщения")
internal val logSources = listOf("Все события", "Туннель", "Деплой", "Система")

internal fun filterLogEntry(entry: LogEntry, level: String, source: String): LogEntry? {
    val entryLevel = effectiveLogLevel(entry)
    val levelMatches = when(level) {
        "Ошибки" -> entryLevel == LogLevel.ERR
        "Успех" -> entryLevel == LogLevel.OK
        "Сеть" -> entryLevel == LogLevel.NET
        "TURN" -> entryLevel == LogLevel.TURN
        "Пинг" -> entryLevel == LogLevel.PING
        "Отладка" -> entryLevel == LogLevel.DBG
        "Сообщения" -> entryLevel == LogLevel.LOG
        else -> true
    }
    val category = when {
        entry.key.startsWith("deploy_") -> "Деплой"
        entry.key.startsWith("app_") || entry.key.startsWith("update_") || entry.key.startsWith("foreground_") -> "Система"
        else -> "Туннель"
    }
    if (!levelMatches || (source != "Все события" && category != source)) return null
    val separator = Regex("[\\s─━═\\-_=+|]{3,}")
    val message = entry.message.lineSequence().filterNot {
        val plain = it.replace(Regex("\u001B\\[[0-9;]*m"), "").replace(Regex("^\\[[^]]{1,24}]\\s*"), "").trim()
        separator.matches(plain)
    }.joinToString("\n").trim()
    return message.takeIf(String::isNotEmpty)?.let { entry.copy(message = it) }
}

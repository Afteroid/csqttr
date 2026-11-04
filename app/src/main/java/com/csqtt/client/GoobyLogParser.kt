package com.csqtt.client

/** Native Gooby status is separate from TURN recovery chatter and packet logs. */
internal object GoobyLogParser {
    fun parse(line: String): LogEntry? {
        val text = line.trim()
        if (text == "[КЛИЕНТ] Встроенный Gooby запущен") {
            return entry("started", "[GOOBY] Встроенный Gooby запущен", LogLevel.LOG)
        }
        if (!text.startsWith("[GOOBY]")) return null
        val body = text.removePrefix("[GOOBY]").trim()
        if (body.isEmpty()) return null
        val error = listOf("ошибка", "error", "fail", "timeout", "refused", "[ФАТАЛ]")
            .any { body.contains(it, ignoreCase = true) }
        if (error) return entry("error", text, LogLevel.ERR)
        val (kind, level) = when {
            body.startsWith("TURN/TCP: разделение Allocate применено") -> "turn_applied" to LogLevel.OK
            body.startsWith("TLS: разделение ClientHello применено") -> "tls_applied" to LogLevel.OK
            body.startsWith("Начальные данные не подходят для стратегии;") -> "passthrough" to LogLevel.LOG
            body.startsWith("Выбрана стратегия:") -> "selected" to LogLevel.LOG
            body.startsWith("Обработано Allocate:") -> "totals" to LogLevel.LOG
            else -> "message" to LogLevel.LOG
        }
        return entry(kind, text, level)
    }

    private fun entry(kind: String, message: String, level: LogLevel) = LogEntry(
        key = "gooby_$kind",
        message = message,
        priority = if (level == LogLevel.ERR) 99 else 20,
        isError = level == LogLevel.ERR,
        level = level,
    )
}

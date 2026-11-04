package com.csqtt.client

/** Diagnostic text only; control messages continue to be handled by TunnelEventParser. */
internal object NativeTunnelLogParser {
    private val category = Regex("^\\[(TURN|СЕССИЯ|ВОРКЕР|ГРУППА|СЕТЬ|КРЕД[^] ]*|ДИАГНОСТИКА|REPAIR|WRAP)(?:[^]]*)]")
    private val changingValues = Regex("0x[0-9a-fA-F]+|\\d+")
    private val identity = Regex("\\[(?:СЕССИЯ|ВОРКЕР|ГРУППА|КРЕД)[^]]*]|(?:turns?:)?(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?")
    private val secrets = Regex("(?i)(\\b(?:access_token|refresh_token|session_token|token|password|passwd|credential|secret|authorization|пароль)\\b[\\\"']?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
    private val callLink = Regex("(?i)(https?://vk\\.(?:com|ru)/call/join/)[^\\s)]+")
    private val clientSummary = Regex("^\\[КЛИЕНТ] (?:TURN:|TLS:|Воркеров:|Tunnel IP:|Доменная маршрутизация:|Все воркеры завершены|Получен сигнал завершения)")

    fun redact(message: String): String = message
        .replace(secrets) { "${it.groupValues[1]}[скрыто]" }
        .replace(callLink) { "${it.groupValues[1]}[скрыто]" }

    fun parse(line: String): LogEntry? {
        // Never expose config/control payloads, even if embedded in an error message.
        if (line.contains("CAPTCHA_SOLVE|") || line.contains("TUNCONF:") ||
            line.contains("CSQTT_EVENT") || line.contains("FATAL_") || line.contains("AUTH_RETRY")) return null
        if (line.contains("GETCONF", true)) {
            return LogEntry("native_getconf", "[GETCONF] Обмен конфигурацией с сервером", priority = 60, level = LogLevel.DBG)
        }
        val tag = category.find(line)?.groupValues?.get(1)
        if (tag == null && !clientSummary.containsMatchIn(line)) return null
        // Readiness is already recorded by the structured event, once per worker.
        if (line.contains("[READY]")) return null
        val recovery = TunnelLogPolicy.isInternalRecovery(line)
        val failure = !recovery && (TunnelLogPolicy.isTurnStreamFailure(line) ||
            listOf("ошибка", "error", "failed", "timeout", "таймаут", "не состоял", "refused", "exhausted", "ни один allocation", "аварийно", "группа остановлена").any { line.contains(it, true) })
        val level = when {
            failure -> LogLevel.ERR
            recovery -> LogLevel.DBG
            tag == "ДИАГНОСТИКА" || tag == "REPAIR" -> LogLevel.NET
            tag == null || line.contains("fallback", true) -> LogLevel.NET
            tag == "TURN" && line.contains("Транспорт:") -> LogLevel.NET
            line.contains("[TURN]") -> LogLevel.TURN
            else -> LogLevel.DBG
        }
        val text = redact(line).take(4096)
        // Preserve worker and endpoint identity; only counters may be coalesced.
        val signature = text.replace(changingValues, "#").take(120)
        val identities = identity.findAll(text).joinToString("|") { it.value }
        return LogEntry("native_${level.name}_${identities}_${signature.hashCode()}", text,
            priority = if (failure) 99 else 60, isError = failure, level = level)
    }
}

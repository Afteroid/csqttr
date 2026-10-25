package com.csqtt.client

/**
 * Правила хоста веб-панели на стороне телефона.
 *
 * Дословный порт валидаторов `is_dns_hostname` и `is_private_ipv4` из
 * `app/src/main/assets/deploy.sh`: расхождение правил дало бы расхождение
 * поведения на телефоне и на VPS. Изменения здесь и в скрипте делаются вместе.
 */
internal object PanelHostPolicy {
    private const val DEFAULT_WEB_PORT = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
    private const val DOMAIN_WEB_PORT = 443
    private const val PLACEHOLDER_HOST = "IP"

    /** Обрезка пробелов и приведение к нижнему регистру — общая норма для телефона и скрипта. */
    fun normalizeHost(host: String): String = host.trim().lowercase()

    fun isDomainHost(host: String): Boolean = isDnsHostname(host)

    fun isPrivateHost(host: String): Boolean {
        val octets = parseIpv4(host) ?: return false
        return isPrivateIpv4(octets)
    }

    /** Доменный режим всегда слушает 443 и игнорирует поле ручного web-порта. */
    fun webPortFor(host: String, manualPorts: Boolean, manualWebPort: String): Int =
        when {
            isDomainHost(host) -> DOMAIN_WEB_PORT
            manualPorts -> manualWebPort.toIntOrNull() ?: DEFAULT_WEB_PORT
            else -> DEFAULT_WEB_PORT
        }

    /**
     * Единственный сборщик адреса панели: домен даёт `https://domain/path` без порта,
     * IP — `https://1.2.3.4:46002/path`. Пустой путь не добавляет завершающего слэша.
     */
    fun buildPanelUrl(host: String, webPort: Int, path: String): String {
        val normalized = normalizeHost(host)
        val displayHost = normalized.ifBlank { PLACEHOLDER_HOST }
        val portSuffix = if (isDnsHostname(normalized)) "" else ":$webPort"
        val pathSuffix = when {
            path.isEmpty() -> ""
            path.startsWith("/") -> path
            else -> "/$path"
        }
        return "https://$displayHost$portSuffix$pathSuffix"
    }

    private fun isDnsHostname(raw: String): Boolean {
        val name = normalizeHost(raw)
        if (name.isEmpty() || name.length > 253) return false
        if (!name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        if (name.startsWith('.') || name.endsWith('.') || name.contains("..")) return false
        val labels = name.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return false
        // Последняя метка обязана содержать букву — иначе `1.2.3.4.5` сошёл бы за домен.
        return labels.last().any { it in 'a'..'z' }
    }

    private fun parseIpv4(raw: String): List<Int>? {
        val value = normalizeHost(raw)
        if (value.isEmpty()) return null
        val parts = value.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet > 255) return null
            octet
        }
    }

    private fun isPrivateIpv4(octets: List<Int>): Boolean {
        val first = octets[0]
        val second = octets[1]
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 100 && second in 64..127) ||
            // deploy.sh отклоняет и 198.18.*, и 198.19.* — фактически /15.
            (first == 198 && (second == 18 || second == 19))
    }
}

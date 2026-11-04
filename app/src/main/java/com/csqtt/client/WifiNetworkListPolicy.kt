package com.csqtt.client

internal enum class WifiNetworkListMode(val storedValue: String, val title: String) {
    ALL("all", "На любом Wi-Fi"),
    BLACKLIST("blacklist", "Только в выбранных сетях"),
    WHITELIST("whitelist", "Во всех, кроме выбранных");

    companion object {
        fun fromStored(value: String?): WifiNetworkListMode =
            entries.firstOrNull { it.storedValue == value } ?: ALL
    }
}

internal fun normalizeWifiSsid(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
    if (value.equals("<unknown ssid>", ignoreCase = true)) return null
    val unquoted = if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        value.substring(1, value.length - 1)
    } else {
        value
    }
    return unquoted.trim().takeIf { it.isNotEmpty() }
}

internal fun decodeWifiSsids(raw: String?): Set<String> =
    raw.orEmpty()
        .lineSequence()
        .mapNotNull(::normalizeWifiSsid)
        .toCollection(linkedSetOf())

internal fun encodeWifiSsids(ssids: Collection<String>): String =
    ssids.mapNotNull(::normalizeWifiSsid)
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .joinToString("\n")

/**
 * Whether the Wi-Fi auto-pause gate should pause the VPN for the current Wi-Fi.
 *
 * The existing "Автопауза при Wi-Fi" switch remains the master switch. The default
 * mode is ALL, preserving the old behaviour of pausing on every Wi-Fi.
 * Existing whitelist/blacklist values keep their original meaning during upgrades.
 */
internal fun shouldPauseForWifiNetwork(
    enabled: Boolean,
    wifiConnected: Boolean,
    currentSsid: String?,
    mode: WifiNetworkListMode,
    configuredSsids: Set<String>,
): Boolean {
    if (!enabled || !wifiConnected) return false

    val ssid = normalizeWifiSsid(currentSsid)
    val normalizedConfigured = configuredSsids.mapNotNull(::normalizeWifiSsid).toSet()
    val listed = ssid != null && normalizedConfigured.any { it.equals(ssid, ignoreCase = false) }

    return when (mode) {
        WifiNetworkListMode.ALL -> true
        WifiNetworkListMode.WHITELIST -> !listed
        WifiNetworkListMode.BLACKLIST -> listed
    }
}

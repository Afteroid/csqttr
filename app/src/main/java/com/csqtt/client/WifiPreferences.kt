package com.csqtt.client

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal data class WifiPreferences(
    val enabled: Boolean = false,
    val mode: WifiNetworkListMode = WifiNetworkListMode.ALL,
    val selected: Set<String> = emptySet(),
    val saved: Set<String> = emptySet(),
)

/** Saved names and pause selections are intentionally independent. Legacy selections remain available. */
internal object WifiPreferenceStore {
    val enabledKey = booleanPreferencesKey("auto_pause_on_wifi")
    val modeKey = stringPreferencesKey("wifi_network_list_mode")
    val selectedKey = stringPreferencesKey("wifi_network_list_ssids")
    val savedKey = stringPreferencesKey("wifi_saved_ssids")

    fun read(prefs: Preferences): WifiPreferences {
        val selected = decodeWifiSsids(prefs[selectedKey])
        // Older versions used an implicit whitelist: adding names did not write modeKey.
        val mode = if (prefs[modeKey] == null && selected.isNotEmpty()) WifiNetworkListMode.WHITELIST
            else WifiNetworkListMode.fromStored(prefs[modeKey])
        return WifiPreferences(prefs[enabledKey] ?: false,
            mode, selected,
            decodeWifiSsids(prefs[savedKey]) + selected)
    }

    fun remember(prefs: MutablePreferences, name: String, expectedMode: WifiNetworkListMode? = null,
        requireEnabled: Boolean = false): Boolean {
        val ssid = normalizeWifiSsid(name) ?: return false
        val previous = read(prefs)
        // A stale notification must not apply an action with a different meaning.
        if ((expectedMode != null && previous.mode != expectedMode) || (requireEnabled && !previous.enabled)) return false
        prefs[modeKey] = previous.mode.storedValue
        prefs[savedKey] = encodeWifiSsids(previous.saved + ssid)
        if (previous.mode != WifiNetworkListMode.ALL) prefs[selectedKey] = encodeWifiSsids(previous.selected + ssid)
        return true
    }

    fun select(prefs: MutablePreferences, name: String, selected: Boolean) {
        val ssid = normalizeWifiSsid(name) ?: return
        if (prefs[modeKey] == null) prefs[modeKey] = read(prefs).mode.storedValue
        remember(prefs, ssid)
        val previous = decodeWifiSsids(prefs[selectedKey])
        prefs[selectedKey] = encodeWifiSsids(if (selected) previous + ssid else previous - ssid)
    }

    fun forget(prefs: MutablePreferences, name: String) {
        val previous = read(prefs)
        if (prefs[modeKey] == null) prefs[modeKey] = previous.mode.storedValue
        prefs[savedKey] = encodeWifiSsids(previous.saved - name)
        prefs[selectedKey] = encodeWifiSsids(previous.selected - name)
    }
}

internal object WifiCopy {
    const val TITLE = "Автопауза Wi-Fi"
    const val SAVED = "Сохранённые сети"
    const val NEW_NETWORK = "Обнаружена новая сеть Wi-Fi"
    const val REMEMBER = "Запомнить"
    const val NOT_NOW = "Не сейчас"
    fun rememberLabel(mode: WifiNetworkListMode) = if (mode == WifiNetworkListMode.WHITELIST) "Запомнить и исключить" else REMEMBER
    fun explanation(mode: WifiNetworkListMode) = when (mode) {
        WifiNetworkListMode.ALL -> "Сеть появится в разделе «Сохранённые сети». Автопауза действует на любом Wi-Fi."
        WifiNetworkListMode.BLACKLIST -> "Сеть появится в разделе «Сохранённые сети» и будет отмечена для автопаузы."
        WifiNetworkListMode.WHITELIST -> "Сеть появится в разделе «Сохранённые сети» и будет отмечена как исключение из автопаузы."
    }
    const val RULE_CHANGED = "Правило автопаузы изменилось. Повторите действие."
    const val HELP = "При подключении к Wi-Fi VPN ставится на паузу по выбранному правилу. При уходе с Wi-Fi ранее включённое подключение возобновится. Постоянного сканирования нет."
    fun question(ssid: String) = "Запомнить $ssid?"
    fun remembered(ssid: String, mode: WifiNetworkListMode) = when (mode) {
        WifiNetworkListMode.ALL -> "$ssid добавлена в раздел «Сохранённые сети»."
        WifiNetworkListMode.BLACKLIST -> "$ssid сохранена и отмечена для автопаузы."
        WifiNetworkListMode.WHITELIST -> "$ssid сохранена и исключена из автопаузы."
    }
    fun paused(ssid: String?) = ssid?.let { "Автопауза · $it" } ?: "Автопауза · Wi-Fi"
}

internal fun wifiDiscoveryCandidate(enabled: Boolean, ssid: String?, saved: Set<String>, dismissed: Set<String>): String? =
    normalizeWifiSsid(ssid)?.takeIf { enabled && it !in saved && it !in dismissed }

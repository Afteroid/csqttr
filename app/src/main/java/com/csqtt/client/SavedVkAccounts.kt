package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** Personal tokens only. Link-provided credentials must never enter this archive. */
internal class SavedVkAccounts(
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String?,
) {
    private val ids = stringSetPreferencesKey("saved_vk_account_ids")
    private fun key(id: String) = stringPreferencesKey("saved_vk_account_${id}_encrypted")
    private fun valid(id: String) = id.isNotEmpty() && id.length <= 20 && id.all { it in '0'..'9' }

    fun token(prefs: Preferences, id: String): String? =
        if (!valid(id)) null else prefs[key(id)]?.let { runCatching { decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }

    fun available(prefs: Preferences): List<String> =
        prefs[ids].orEmpty().filter { token(prefs, it) != null }.sorted()

    fun remember(prefs: MutablePreferences, id: String, value: String) {
        if (!valid(id) || value.isBlank()) return
        if (token(prefs, id) != value) prefs[key(id)] = encrypt(value)
        prefs[ids] = prefs[ids].orEmpty() + id
    }

    fun forget(prefs: MutablePreferences, id: String) {
        if (!valid(id)) return
        prefs.remove(key(id))
        prefs[ids] = prefs[ids].orEmpty() - id
    }

    fun rememberLegacy(prefs: MutablePreferences, id: String, value: String) {
        if (token(prefs, id) == null) remember(prefs, id, value)
    }
}

package com.csqtt.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A separate VPS cursor over the same profile records; selecting a VPS never selects a VPN profile. */
internal class DeployScopedDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    private val connectionCursor = intPreferencesKey("active_profile")
    private val deployCursor = intPreferencesKey("active_deploy_profile")

    private fun initialCursor(prefs: Preferences): Int {
        prefs[deployCursor]?.takeIf { it in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX }?.let { return it }
        fun value(key: String, profile: Int) = prefs[stringPreferencesKey(if (profile == 0) key else "${key}_$profile")].orEmpty()
        val slots = CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX
        return slots.firstOrNull { value("deploy_ip", it).isNotBlank() }
            ?: slots.firstOrNull { value("peer", it).isBlank() && value("csqtt_link", it).isBlank() }
            ?: slots.first
    }

    override val data: Flow<Preferences> = delegate.data.map { prefs ->
        prefs.toMutablePreferences().apply { this[connectionCursor] = initialCursor(prefs) }.toPreferences()
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = delegate.updateData { original ->
            val scoped = original.toMutablePreferences().apply {
                this[connectionCursor] = initialCursor(original)
            }
            transform(scoped).toMutablePreferences().apply {
                this[deployCursor] = this[connectionCursor] ?: 0
                original[connectionCursor]?.let { this[connectionCursor] = it } ?: remove(connectionCursor)
            }.toPreferences()
        }
        return updated.toMutablePreferences().apply { this[connectionCursor] = updated[deployCursor] ?: 0 }.toPreferences()
    }
}

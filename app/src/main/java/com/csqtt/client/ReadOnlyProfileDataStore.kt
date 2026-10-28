package com.csqtt.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.map

/** A stable profile view for diagnostics. Neither cursor nor profile data can be changed. */
internal class ReadOnlyProfileDataStore(delegate: DataStore<Preferences>, profile: Int) : DataStore<Preferences> {
    init { require(profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) }
    override val data = delegate.data.map {
        it.toMutablePreferences().apply { this[intPreferencesKey("active_profile")] = profile }.toPreferences()
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        error("Diagnostic profile settings are read-only")
}

package com.csqtt.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReadOnlyProfileDataStoreTest {
    @Test fun diagnosticsKeepTheirOwnProfileWithoutChangingStoredCursorsOrAllowingWrites() = runTest {
        val active = intPreferencesKey("active_profile")
        val deploy = intPreferencesKey("active_deploy_profile")
        val base = object : DataStore<Preferences> {
            override val data = MutableStateFlow(preferencesOf(active to 2, deploy to 3))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
        }
        val diagnostic = ReadOnlyProfileDataStore(base, 4)
        assertEquals(4, diagnostic.data.first()[active])
        base.edit { it[active] = 5 }
        assertEquals(4, diagnostic.data.first()[active])
        assertEquals(5, base.data.first()[active])
        assertEquals(3, base.data.first()[deploy])
        try { diagnostic.edit { it[active] = 7 }; fail("Diagnostic writes must be rejected") } catch (_: IllegalStateException) { }
        assertEquals(5, base.data.first()[active])
    }
}

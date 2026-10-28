package com.csqtt.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeployScopedDataStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    private val vpn = intPreferencesKey("active_profile")
    private val vps = intPreferencesKey("active_deploy_profile")

    @Test fun selectingVpsPreservesVpnProfileAndUpdatesIndependentCursor() = runTest {
        val base = MemoryStore()
        base.edit { it[vpn] = 3; it[vps] = 1 }
        val deploy = DeployScopedDataStore(base)
        assertEquals(1, deploy.data.first()[vpn])
        deploy.edit { it[vpn] = 5 }
        assertEquals(3, base.data.first()[vpn])
        assertEquals(5, base.data.first()[vps])
        assertEquals(5, deploy.data.first()[vpn])
    }

    @Test fun editsKeepServerDataAndConcurrentConnectionSelection() = runTest {
        val base = MemoryStore()
        base.edit { it[vpn] = 2; it[vps] = 4 }
        val deploy = DeployScopedDataStore(base)
        val server = stringPreferencesKey("deploy_ip_4")
        deploy.edit { it[server] = "203.0.113.10" }
        base.edit { it[vpn] = 6 }
        deploy.edit { it[stringPreferencesKey("deploy_ssh_port_4")] = "5052" }
        assertEquals("203.0.113.10", base.data.first()[server])
        assertEquals(6, base.data.first()[vpn])
        assertEquals(4, deploy.data.first()[vpn])
    }

    @Test fun firstVpsUsesAFreeSlotInsteadOfExistingConnection() = runTest {
        val base = MemoryStore()
        base.edit { it[vpn] = 0; it[stringPreferencesKey("peer")] = "203.0.113.30" }
        val deploy = DeployScopedDataStore(base)
        assertEquals(1, deploy.data.first()[vpn])
        assertEquals(0, base.data.first()[vpn])
    }
}

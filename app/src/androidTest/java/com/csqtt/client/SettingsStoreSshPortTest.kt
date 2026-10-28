package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreSshPortTest {
    @Test fun deletedConnectionCannotBeRestoredByLateTunnelOrVpsAutosaves() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val deploy = SettingsStore(context, deployScope = true)
        val vps = store.findFirstFreeDeployProfile()!!
        val draft = DeploySettingsSnapshot(profile = vps, ip = "203.0.113.197", serverName = "Deletion fixture",
            mainPassword = "DeleteTestPass12", serverPeerPort = 49700, serverWebPort = 49702)
        val connection = deploy.saveDeployProfileDraft(draft, restoreConnection = true)!!
        val old = store.readProfileConnectionSettings(connection)
        store.clearProfile(connection)
        store.saveProfileConnectionSettings(connection, old.peer, old.serverPeerPort, old.connectionPassword,
            old.workersPerHash, expectedRevision = old.revision)
        repeat(3) { deploy.saveDeployProfileDraft(draft) }
        deploy.saveDeployedServer(vps, draft.ip, draft.serverPeerPort, draft.serverWebPort)
        deploy.saveConnectionPassword(draft.mainPassword, forProfile = connection)
        assertFalse(connection in store.occupiedProfiles.first())
        assertEquals("", store.readProfileConnectionSettings(connection).peer)
        assertEquals("", store.readProfileConnectionSettings(connection).connectionPassword)
        // An explicit new attempt is allowed, while the pre-deletion draft is still obsolete.
        assertEquals(connection, deploy.saveDeployProfileDraft(draft, restoreConnection = true))
        store.saveProfileConnectionSettings(connection, "stale.invalid", 46000, "stale", 10, expectedRevision = old.revision)
        assertEquals(draft.ip, store.readProfileConnectionSettings(connection).peer)
        deploy.clearDeployProfile(vps)
        store.clearProfile(connection)
    }
    @Test fun savingDeployAttemptReusesExistingConnectionAndKeepsCredentialsWithoutNetwork() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val deploy = SettingsStore(context, deployScope = true)
        val connection = store.findFirstFreeDeployProfile()!!
        store.saveProfileConnectionSettings(connection, "203.0.113.199", 49900, "", 30)
        val vps = store.findFirstFreeDeployProfile()!!
        val occupied = store.occupiedProfiles.first()
        val attempt = DeploySettingsSnapshot(profile = vps, serverName = "Attempt fixture", ip = "203.0.113.199",
            sshPort = "5052", sshLogin = "fixture-user", sshPassword = "fixture-ssh-password",
            serverPeerPort = 49900, serverWebPort = 49902, mainPassword = "MainPassword1234",
            webLogin = "fixture-admin", webPassword = "fixture-panel-password")
        assertEquals(connection, deploy.saveDeployProfileDraft(attempt))
        assertEquals(connection, deploy.linkedConnectionProfile(vps))
        assertEquals(occupied, store.occupiedProfiles.first())
        assertEquals(attempt.mainPassword, store.readProfileConnectionSettings(connection).connectionPassword)
        deploy.saveDeployProfileDraft(attempt.copy(mainPassword = "NewPassword12345"))
        assertEquals(occupied, store.occupiedProfiles.first())
        // A failed attempt must retain the previous working password on an existing connection.
        assertEquals(attempt.mainPassword, store.readProfileConnectionSettings(connection).connectionPassword)
        val saved = SettingsStore(context, readOnlyProfile = vps).deploySettingsSnapshot.first { it.isLoaded }
        assertEquals("NewPassword12345", saved.mainPassword)
        assertEquals(attempt.sshPassword, saved.sshPassword)
        assertEquals(attempt.webPassword, saved.webPassword)
        assertEquals(attempt.webLogin, saved.webLogin)
        assertEquals(49902, saved.serverWebPort)
        assertEquals(30, store.readProfileConnectionSettings(connection).workersPerHash)
        deploy.clearDeployProfile(vps)
        store.clearProfile(connection)
    }
    @Test fun lastTunnelModesAreRestoredIndependentlyForEachProfile() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val previous = store.activeProfile.first()
        store.saveActiveProfile(26)
        store.saveVkHashMode("auto_js")
        store.saveSrtpMode(CsqttConstants.SrtpMode.WRAP)
        store.saveTurnTransport(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS)
        store.saveSelectedTurnSubnet("fixture-subnet", "10")
        store.saveExtraWorkers(true)
        store.saveProfileWorkersPerHash(26, 60)
        store.saveActiveProfile(27)
        store.saveVkHashMode("manual")
        store.saveSrtpMode(CsqttConstants.SrtpMode.DTLS)
        store.saveTurnTransport("udp")
        store.saveSelectedTurnSubnet("", "ALL")
        store.saveExtraWorkers(false)
        store.saveProfileWorkersPerHash(27, 20)
        // A late callback belongs to its captured slot, even after selection moved.
        store.saveVkHashMode("auto_api", forProfile = 26)
        store.saveActiveProfile(26)
        val a = SettingsStore(context, readOnlyProfile = 26)
        assertEquals("auto_api", a.vkHashMode.first())
        assertEquals(CsqttConstants.SrtpMode.WRAP, a.srtpMode.first())
        assertEquals(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS, a.turnTransport.first())
        assertEquals("fixture-subnet", a.selectedTurnSubnet.first())
        assertEquals("10", a.turnSubnetIpLimit.first())
        assertTrue(a.extraWorkers.first())
        assertEquals(60, a.workersPerHash.first())
        store.saveActiveProfile(27)
        assertEquals("manual", store.vkHashMode.first())
        assertEquals("udp", store.turnTransport.first())
        assertEquals(CsqttConstants.SrtpMode.DTLS, store.srtpMode.first())
        assertEquals("", store.selectedTurnSubnet.first())
        assertFalse(store.extraWorkers.first())
        assertEquals(20, store.workersPerHash.first())
        store.saveActiveProfile(previous)
    }
    @Test fun ownerDraftCreatesConnectionAndKeepsGeneratedDefaultsAcrossThemes() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deploy = SettingsStore(context, deployScope = true)
        val store = SettingsStore(context)
        val slot = store.findFirstFreeDeployProfile()!!
        deploy.ensureDeployProfileDefaults(slot)
        val scoped = SettingsStore(context, readOnlyProfile = slot)
        val defaults = scoped.deploySettingsSnapshot.first { it.isLoaded }
        assertEquals("admin", defaults.webLogin)
        assertEquals(16, defaults.mainPassword.length)
        assertEquals(16, defaults.webPassword.length)
        deploy.ensureDeployProfileDefaults(slot)
        assertEquals(defaults.mainPassword, scoped.deploySettingsSnapshot.first().mainPassword)
        deploy.saveDeployProfileDraft(defaults.copy(ip = "203.0.113.94", serverName = "Owner fixture", serverPeerPort = 49400, serverWebPort = 49402), restoreConnection = true)
        val connection = store.readProfileConnectionSettings(slot)
        assertEquals("203.0.113.94", connection.peer)
        assertEquals(49400, connection.serverPeerPort)
        assertEquals(defaults.mainPassword, connection.connectionPassword)
        assertEquals("Owner fixture", store.profileNames.first()[slot])
        val occupied = store.occupiedProfiles.first()
        for (theme in listOf("light", "dark", "light")) {
            store.saveThemeMode(theme)
            assertEquals(occupied, store.occupiedProfiles.first())
            assertEquals(defaults.webPassword, scoped.deploySettingsSnapshot.first().webPassword)
        }
        store.saveThemeMode("dark")
        deploy.clearDeployProfile(slot)
        store.clearProfile(slot)
    }

    @Test fun personalVkAccountIsSharedAndLogoutClearsItAcrossProfiles() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val original = store.activeProfile.first()
        store.saveActiveProfile(29)
        store.saveVkAccessToken("fixture-personal-token", "12345")
        store.saveActiveProfile(30)
        assertEquals("fixture-personal-token", store.vkAccessToken.first())
        assertEquals("12345", store.vkAccessTokenUserId.first())
        store.clearVkAccessToken()
        store.saveActiveProfile(29)
        assertEquals("", store.vkAccessToken.first())
        store.saveActiveProfile(original)
    }
    @Test fun vpsDraftKeepsAllCredentialsInItsSlotWithoutChangingWorkersOrVpnCursor() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connections = SettingsStore(context)
        val deploy = SettingsStore(context, deployScope = true)
        val vpnCursor = connections.activeProfile.first()
        connections.saveProfileWorkersPerHash(6, 30)
        deploy.saveActiveProfile(7)
        val first = DeploySettingsSnapshot(profile = 6, serverName = "VPS A", ip = "203.0.113.66",
            sshLogin = "deploy-a", sshPassword = "fixture-password-a", sshPort = "5052",
            serverPeerPort = 46600, serverWebPort = 46602, mainPassword = "TestMainPass1234",
            webLogin = "admin-a", webPassword = "fixture-web-a", sshKeysMode = true)
        deploy.saveDeployProfileDraft(first)
        deploy.saveSshKeys("fixture-private-key-a", "fixture-key-pass-a", "fixture-certificate-a", forProfile = 6)
        deploy.saveDeployProfileDraft(first.copy(profile = 7, serverName = "VPS B", ip = "203.0.113.77",
            sshLogin = "deploy-b", sshPassword = "fixture-password-b", sshPort = "5053",
            serverPeerPort = 47700, serverWebPort = 47702, sshKeysMode = false))
        // Write A while B remains selected: late autosaves must not overwrite B or move cursors.
        deploy.saveDeployProfileDraft(first.copy(webLogin = "updated-admin-a"))
        assertEquals(7, deploy.activeProfile.first())
        assertEquals(vpnCursor, connections.activeProfile.first())
        val a = SettingsStore(context, readOnlyProfile = 6).deploySettingsSnapshot.first { it.isLoaded }
        val b = SettingsStore(context, readOnlyProfile = 7).deploySettingsSnapshot.first { it.isLoaded }
        assertEquals("203.0.113.66", a.ip)
        assertEquals("5052", a.sshPort)
        assertEquals(46600, a.serverPeerPort)
        assertEquals(46602, a.serverWebPort)
        assertEquals("deploy-a", a.sshLogin)
        assertEquals("fixture-password-a", a.sshPassword)
        assertEquals("fixture-private-key-a", a.sshPrivateKey)
        assertEquals("fixture-key-pass-a", a.sshKeyPassphrase)
        assertEquals("fixture-certificate-a", a.sshCertificate)
        assertEquals("updated-admin-a", a.webLogin)
        assertTrue(a.sshKeysMode)
        assertEquals("203.0.113.77", b.ip)
        assertEquals("5053", b.sshPort)
        assertEquals(47700, b.serverPeerPort)
        assertEquals("deploy-b", b.sshLogin)
        assertFalse(b.sshKeysMode)
        assertEquals(30, connections.readProfileConnectionSettings(6).workersPerHash)
        connections.saveProfileConnectionSettings(6, "203.0.113.66", 46600, "fixture-tunnel-password", 30)
        deploy.clearDeployProfile(forProfile = 6)
        val connection = connections.readProfileConnectionSettings(6)
        assertEquals("203.0.113.66", connection.peer)
        assertEquals(46600, connection.serverPeerPort)
        assertEquals("fixture-tunnel-password", connection.connectionPassword)
        val removed = SettingsStore(context, readOnlyProfile = 6).deploySettingsSnapshot.first { it.isLoaded }
        assertEquals("", removed.ip)
        assertEquals("", removed.sshPrivateKey)
        assertEquals("203.0.113.77", SettingsStore(context, readOnlyProfile = 7).deploySettingsSnapshot.first { it.isLoaded }.ip)
    }
    @Test
    fun savingAutomaticServicePortsPreservesSshPort() = runBlocking {
        val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = store.deploySettingsSnapshot.first { it.isLoaded }
        try {
            store.saveManualPortsEnabled(true)
            store.savePorts(47000, 47002, "5052")
            // V2 saves the connection first, then the service-port mode.
            store.saveManualPortsEnabled(false)

            val saved = store.deploySettingsSnapshot.first { it.isLoaded }
            assertEquals("5052", saved.sshPort)
            assertFalse(saved.manualPortsEnabled)
            assertEquals(CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT, saved.serverPeerPort)
            assertEquals(CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT, saved.serverWebPort)
        } finally {
            store.saveManualPortsEnabled(before.manualPortsEnabled)
            store.savePorts(before.serverPeerPort, before.serverWebPort, before.sshPort)
        }
    }
}

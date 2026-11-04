package com.csqtt.client

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.navigation.CsqttV2Root
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Emulator fixtures only. Never starts VPN, VK or server operations. */
class GoobyInterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: SettingsStore

    @Before fun setup() {
        store = SettingsStore(compose.activity)
        runBlocking {
            store.clearProfile(0); store.clearProfile(1)
            store.saveActiveProfile(0)
            store.saveUiOwnerMode(true)
            store.saveThemeMode("dark")
            store.saveUiDesign(UiDesignPresets.ORIGINAL_250)
            store.saveUpdateCheckIntervalHours(UPDATE_CHECK_NEVER)
            store.saveProfileConnectionSettings(0, "203.0.113.10", 46000, "FixturePassword", 70)
            store.saveProfileName(0, "Gooby demo")
            store.saveTurnTransport(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS)
            store.saveVkHashMode("manual")
        }
        compose.setContent { CsqttV2Root(store) }
    }

    @After fun cleanup() {
        TunnelManager.starting.value = false
        runBlocking { store.saveGoobyEnabled(false, 0); store.saveGoobyEnabled(false, 1); store.saveActiveProfile(0) }
    }

    @Test fun switchPersistsPerProfileAndIsInactiveOnUdp() {
        compose.onNodeWithText("Туннель").performClick()
        compose.onNodeWithTag("gooby-switch").assertIsOff().performClick()
        compose.waitUntil(5000) { runBlocking { store.goobyEnabled.first() } }
        compose.onNodeWithTag("gooby-switch").assertIsOn()
        for (theme in listOf("dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            compose.waitForIdle()
            compose.onNodeWithTag("gooby-switch").assertIsDisplayed()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.getExternalFilesDir(null), "gooby-$theme.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { TunnelManager.starting.value = true }
        compose.onNodeWithTag("gooby-switch").assertIsNotEnabled()
        compose.runOnIdle { TunnelManager.starting.value = false }
        runBlocking {
            store.saveActiveProfile(1)
            assertFalse(store.goobyEnabled.first())
            store.saveActiveProfile(0)
            assertTrue(store.goobyEnabled.first())
            store.saveTurnTransport("udp")
            assertTrue(store.goobyEnabled.first()) // retain preference, but never activate for UDP
            assertFalse(GoobyPolicy.isActive(store.goobyEnabled.first(), store.turnTransport.first()))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("gooby-switch").assertDoesNotExist()
    }

    @Test fun exportImportAndProfileReuseKeepSafeDefaults() = runBlocking {
        val before = store.readProfileConnectionSettings(0)
        store.saveGoobyEnabled(true)
        val exported = store.exportConfigJson(true, false, false, false)
        assertTrue(JSONObject(exported).getJSONObject("tunnel").getBoolean("gooby_enabled"))
        store.saveGoobyEnabled(false)
        assertTrue(store.importConfigJson(exported, true, false, false, false))
        assertTrue(store.goobyEnabled.first())
        assertEquals(before.peer, store.readProfileConnectionSettings(0).peer)
        val legacy = JSONObject(exported).also { it.getJSONObject("tunnel").remove("gooby_enabled") }
        assertTrue(store.importConfigJson(legacy.toString(), true, false, false, false))
        assertFalse(store.goobyEnabled.first())
        store.saveGoobyEnabled(true)
        store.clearProfile(0)
        store.saveActiveProfile(0)
        assertFalse(store.goobyEnabled.first())
    }

    @Test fun nativeCoreContainsEmbeddedGoobyAndRejectsUdp() {
        val binary = File(compose.activity.applicationInfo.nativeLibraryDir, "libclient.so")
        fun run(vararg arguments: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(binary.absolutePath) + arguments).redirectErrorStream(true).start()
            try {
                assertTrue("Native core timed out", process.waitFor(10, TimeUnit.SECONDS))
                return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
            } finally { process.destroyForcibly() }
        }
        val (udpExit, error) = run("--gooby", "--turn-transport", "udp")
        assertNotEquals(0, udpExit)
        assertTrue(error.contains("Gooby требует TCP/TLS"))
    }
}

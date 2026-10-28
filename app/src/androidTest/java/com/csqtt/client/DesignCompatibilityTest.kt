package com.csqtt.client

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Switch actual Activity designs without SSH, VPN or VK authentication. */
class DesignCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bothDesignsShareBatteryPreferencesAndPreserveSelectedConnection() {
        val store = SettingsStore(compose.activity)
        val oldMode = runBlocking { store.batterySaverMode.first() }
        val oldPercent = runBlocking { store.batterySaverPercent.first() }
        val oldOwner = runBlocking { store.uiOwnerMode.first() }
        val oldShowLogs = runBlocking { store.participantShowLogs.first() }
        val profile = runBlocking { store.activeProfile.first() }
        val connection = runBlocking { store.readProfileConnectionSettings(profile) }
        try {
            runBlocking {
                store.saveUpdateCheckIntervalHours(UPDATE_CHECK_NEVER)
                store.saveUiDesign("v2")
                store.saveUiOwnerMode(false)
                store.saveParticipantShowLogs(false)
                store.saveBatterySaverMode("level")
                store.saveBatterySaverPercent(20)
            }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Настройки").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Новый").performScrollTo().performClick()
            compose.onNodeWithText("Классический").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Быстрые настройки").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Быстрые настройки").performClick()
            compose.onNodeWithText(BatterySaver.TITLE).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Свой уровень").assertExists()
            compose.onNodeWithText("≤ 20 %").assertExists()
            compose.onNodeWithText("Показывать логи").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { store.participantShowLogs.first() } }
            compose.onNodeWithText("Классический").performScrollTo().performClick()
            compose.onNodeWithText("Новый").performClick()
            compose.waitUntil(5000) { runBlocking { store.uiDesign.first() == "v2" } }
            compose.onNodeWithText(BatterySaver.TITLE).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("≤ 20 %").assertExists()
            compose.onNodeWithText("Показывать вкладку «Логи»").performScrollTo().assertExists()
            runBlocking {
                assertEquals(profile, store.activeProfile.first())
                val after = store.readProfileConnectionSettings(profile)
                assertEquals(connection.peer, after.peer)
                assertEquals(connection.serverPeerPort, after.serverPeerPort)
                assertEquals(connection.connectionPassword, after.connectionPassword)
                assertEquals(connection.workersPerHash, after.workersPerHash)
            }
        } finally {
            runBlocking { store.saveUiDesign("v2"); store.saveUiOwnerMode(oldOwner); store.saveParticipantShowLogs(oldShowLogs); store.saveBatterySaverMode(oldMode); store.saveBatterySaverPercent(oldPercent) }
        }
    }
}

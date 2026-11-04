package com.csqtt.client

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ui.legacy.FoundServer
import com.csqtt.client.ui.legacy.ServerInventory
import com.csqtt.client.ui.legacy.ServerPlace
import com.csqtt.client.ui.v2.design.CsqttV2Theme
import com.csqtt.client.ui.v2.screens.deploy.V2DeployScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Simulated inventory and removal only. No SSH connection or real uninstall. */
class V2ServerRemovalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: SettingsStore
    @Volatile private var checks = 0
    @Volatile private var removals = 0
    @Volatile private var failCheck = false
    private val server = FoundServer(ServerPlace.STANDARD, "systemd", "active", "2.5.0", 46000, 46002, "csqtt")

    @Before fun showDeploy() {
        DeployManager.forceReset()
        store = SettingsStore(compose.activity, deployScope = true)
        runBlocking {
            store.saveDeployProfileDraft(DeploySettingsSnapshot(profile = 25, serverName = "Removal fixture",
                ip = "203.0.113.125", sshLogin = "fixture", sshPassword = "fixture-password", sshPort = "5052"))
            store.saveActiveProfile(25)
        }
        compose.setContent {
            val saved by store.deploySettingsSnapshot.collectAsStateWithLifecycle(DeploySettingsSnapshot())
            CsqttV2Theme {
                V2DeployScreen(store, saved, "Fixture", {}, inventoryReader = {
                    checks++
                    delay(400)
                    if (failCheck) error("fixture-check-failure")
                    ServerInventory(listOf(server), emptyList())
                }, uninstallAction = {
                    assertEquals(server, it)
                    removals++
                })
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Удалить").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun requestRemoval() {
        compose.onNodeWithText("Удалить").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Удалить CSQTT с сервера?").assertIsDisplayed()
        assertEquals(0, checks)
        assertEquals(0, removals)
        assertFalse(DeployManager.isDeploying.value)
    }

    @Test fun cancellationBeforeSshKeepsVpsAndDoesNotStartCheck() {
        requestRemoval()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Удалить CSQTT с сервера?").assertDoesNotExist()
        assertEquals(0, checks)
        runBlocking { assertEquals("203.0.113.125", store.deploySettingsSnapshot.first().ip) }
    }

    @Test fun failedCheckRemainsVisibleAndDoesNotRemoveAnything() {
        failCheck = true
        requestRemoval()
        compose.onNodeWithText("Продолжить").performClick()
        compose.onNodeWithText("Проверка перед удалением").assertIsDisplayed()
        compose.waitUntil(5000) { checks == 1 && !DeployManager.isDeploying.value }
        compose.waitUntil(5000) { compose.onAllNodesWithText("fixture-check-failure", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("fixture-check-failure", substring = true).assertIsDisplayed()
        assertEquals(0, removals)
        compose.onNodeWithText("Закрыть").performClick()
        runBlocking { assertEquals("203.0.113.125", store.deploySettingsSnapshot.first().ip) }
    }

    @Test fun onlyFinalTypedConfirmationRequestsUninstall() {
        requestRemoval()
        compose.onNodeWithText("Продолжить").performClick()
        compose.waitUntil(5000) { checks == 1 && !DeployManager.isDeploying.value }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Удалить:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Удалить:", substring = true).performClick()
        compose.onNode(hasText("Удалить") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        assertEquals(0, removals)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("uninstall-confirm-input"))).performTextReplacement("да")
        compose.onNode(hasText("Удалить") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
        compose.waitUntil(5000) { removals == 1 }
        assertFalse(DeployManager.isDeploying.value)
    }
}

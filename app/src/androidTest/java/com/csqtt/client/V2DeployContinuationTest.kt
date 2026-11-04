package com.csqtt.client

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ui.legacy.DeployActionMode
import com.csqtt.client.ui.legacy.PlanAction
import com.csqtt.client.ui.legacy.ServerInventory
import com.csqtt.client.ui.legacy.FoundServer
import com.csqtt.client.ui.legacy.ServerPlace
import com.csqtt.client.ui.v2.design.CsqttV2Theme
import com.csqtt.client.ui.v2.screens.deploy.V2DeployCheckState
import com.csqtt.client.ui.v2.screens.deploy.V2DeployScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** No network: exercise the real install buttons and async check while the tab is disposed. */
class V2DeployContinuationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val inventory = CompletableDeferred<ServerInventory>()
    private var showDeploy by mutableStateOf(true)
    private val checkState = V2DeployCheckState()
    @Volatile private var checks = 0
    @Volatile private var deployments = 0
    private var chosen: Pair<DeployActionMode, PlanAction>? = null

    @After fun stopFixture() {
        inventory.cancel()
        DeployManager.forceReset()
    }

    @Before fun showScreen() {
        DeployManager.forceReset()
        val store = SettingsStore(compose.activity, deployScope = true)
        runBlocking {
            store.saveDeployProfileDraft(DeploySettingsSnapshot(profile = 24,
                serverName = "Install fixture", ip = "203.0.113.124", sshLogin = "fixture",
                sshPassword = "fixture-password", sshPort = "5052", serverPeerPort = 47000,
                serverWebPort = 47002, manualPortsEnabled = true, mainPassword = "test-password", webLogin = "fixture",
                webPassword = "FixturePanel12345"))
            store.saveActiveProfile(24)
        }
        compose.setContent {
            val saved by store.deploySettingsSnapshot.collectAsStateWithLifecycle(DeploySettingsSnapshot())
            CsqttV2Theme {
                if (showDeploy) {
                    V2DeployScreen(store, saved, "Fixture", {}, checkState = checkState,
                        inventoryReader = { checks++; inventory.await() },
                        deployAction = { mode, action -> deployments++; chosen = mode to action })
                } else {
                    Button(onClick = { showDeploy = true }) { Text("Вернуться из логов") }
                }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Установить").fetchSemanticsNodes().size == 1 }
    }

    private fun startCheck() {
        compose.onNodeWithText("Установить").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onNodeWithText("Установка CSQTT").isDisplayed() }
        compose.onNodeWithText("Установка CSQTT").assertIsDisplayed()
        compose.onNode(hasText("Установить") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5000) { checks == 1 }
        assertEquals(0, deployments)
    }

    private fun assertInstallStarted() {
        compose.waitUntil(5000) { deployments == 1 }
        assertEquals(DeployActionMode.Install, chosen!!.first)
        assertEquals(47000, chosen!!.second.peerPort)
        assertEquals(47002, chosen!!.second.webPort)
        assertEquals(1, checks)
    }

    @Test fun installContinuesAfterCheckCompletesInLogs() {
        startCheck()
        compose.runOnIdle { showDeploy = false }
        compose.onNodeWithText("Вернуться из логов").assertIsDisplayed()
        inventory.complete(ServerInventory(emptyList(), emptyList()))
        compose.waitUntil(5000) { !DeployManager.isDeploying.value }
        assertInstallStarted()
        compose.onNodeWithText("Вернуться из логов").performClick()
        compose.onNodeWithText("На сервере").assertDoesNotExist()
        assertEquals(1, deployments)
    }

    @Test fun installContinuesWithoutLeavingDeploy() {
        startCheck()
        inventory.complete(ServerInventory(emptyList(), emptyList()))
        assertInstallStarted()
    }

    @Test fun progressLineShowsCurrentStageAfterReturningFromLogs() {
        compose.runOnIdle {
            DeployManager.startDeploy()
            DeployManager.updateProgress(0.45f, "Загрузка сервера…")
        }
        compose.onNodeWithTag("deploy-progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("45 %").assertIsDisplayed()
        compose.runOnIdle { showDeploy = false }
        compose.onNodeWithText("Вернуться из логов").performClick()
        compose.onNodeWithTag("deploy-progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Загрузка сервера…").assertIsDisplayed()
        compose.onNodeWithText("45 %").assertIsDisplayed()
        compose.runOnIdle { DeployManager.forceReset() }
    }

    @Test fun completedPlanSurvivesOpeningLogsAndCanBeCancelled() {
        startCheck()
        compose.runOnIdle { showDeploy = false }
        inventory.complete(ServerInventory(listOf(FoundServer(ServerPlace.STANDARD,
            "systemd", "running", "2.1.9", 46000, 46002, "csqtt.service")), emptyList()))
        compose.waitUntil(5000) { checkState.plan.value != null }
        compose.onNodeWithText("Вернуться из логов").performClick()
        compose.onNodeWithText("На сервере").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { showDeploy = false }
        compose.onNodeWithText("Вернуться из логов").performClick()
        compose.onNodeWithText("На сервере").assertDoesNotExist()
        assertEquals(0, deployments)
    }
}

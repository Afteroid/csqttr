package com.csqtt.client

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.navigation.CsqttV2Root
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Emulator-only fixtures: no SSH, VPN, ping, or real credentials. */
class ReviewedDeployInterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: SettingsStore
    private lateinit var deploy: SettingsStore

    @Before fun showApp() {
        store = SettingsStore(compose.activity)
        deploy = SettingsStore(compose.activity, deployScope = true)
        runBlocking {
            store.saveUiOwnerMode(true)
            store.saveThemeMode("dark")
            store.saveUiDesign(UiDesignPresets.ORIGINAL_250)
            store.saveUiScalePercent(100)
            store.saveUpdateCheckIntervalHours(UPDATE_CHECK_NEVER)
            for (i in 0..1) {
                store.saveProfileConnectionSettings(i, "203.0.113.${i+1}", 46000, "FixturePassword", 70)
                store.saveProfileName(i, "Connection ${i+1}")
            }
            for (i in 10..11) deploy.saveDeployProfileDraft(DeploySettingsSnapshot(
                profile = i, serverName = "VPS $i", ip = "203.0.113.$i", sshLogin = "fixture",
                sshPassword = "FixtureSshPassword", mainPassword = "FixtureTunnel123", webLogin = "admin", webPassword = "FixtureWeb123",
            ))
            deploy.saveActiveProfile(10)
            store.saveActiveProfile(0)
        }
        compose.setContent { CsqttV2Root(store) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Connection 1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openDeploy() { compose.onNodeWithText("Деплой").performClick(); compose.onNodeWithTag("deploy-card").assertIsDisplayed() }
    private fun shot(name: String) {
        File(compose.activity.getExternalFilesDir(null), "debug28-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun independentDeploySelectionAndSharedTunnelSelection() {
        compose.onNodeWithText("Connection 2").performClick()
        compose.onNodeWithText("Туннель").performClick()
        compose.onNodeWithText("Connection 2").assertIsDisplayed()
        openDeploy()
        compose.onNodeWithText("VPS 10").assertIsDisplayed().performClick()
        compose.onNodeWithText("VPS 11").performClick()
        compose.waitUntil(5000) { runBlocking { deploy.activeProfile.first() == 11 } }
        assertEquals(1, runBlocking { store.activeProfile.first() })
        compose.onNodeWithText("Туннель").performClick()
        compose.onNodeWithText("Connection 2").performClick()
        compose.onNodeWithText("Connection 1").performClick()
        compose.waitUntil(5000) { runBlocking { store.activeProfile.first() == 0 } }
        compose.onNodeWithText("Подкл.").performClick()
        compose.onNodeWithText("Connection 1").assertIsDisplayed()
        assertEquals(11, runBlocking { deploy.activeProfile.first() })
    }

    @Test fun manualPortsAuthorizationAndPlusMatchReviewedForm() {
        openDeploy()
        compose.onNodeWithContentDescription("Порт туннеля").assertDoesNotExist()
        compose.onNodeWithText("Ручные порты").performClick()
        val ports = compose.onNodeWithContentDescription("Порт туннеля").getUnclippedBoundsInRoot()
        val login = compose.onNodeWithContentDescription("Логин SSH").getUnclippedBoundsInRoot()
        assertTrue(ports.bottom <= login.top)
        shot("manual-ports")
        compose.onNodeWithText("Ручные порты").performClick()
        compose.onNodeWithText("Авторизация").performClick()
        compose.onNodeWithContentDescription("Пароль туннеля").assertExists()
        compose.onNodeWithText("Показать пароли").performClick()
        compose.onNodeWithText("Скрыть пароли").assertIsDisplayed()
        shot("authorization")
        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithContentDescription("Изменить название профиля").performClick()
        compose.onNodeWithContentDescription("Название").performTextReplacement("Renamed VPS")
        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("Renamed VPS").assertIsDisplayed()
        assertEquals("203.0.113.10", runBlocking { deploy.deploySettingsSnapshot.first() }.ip)
        compose.onNodeWithContentDescription("Добавить VPS").performClick()
        compose.waitUntil(5000) { runBlocking { deploy.activeProfile.first() != 10 } }
        compose.onNode(isDialog()).assertDoesNotExist()
        for (label in listOf("Адрес сервера", "Логин SSH", "Пароль SSH")) {
            assertEquals("", compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        assertEquals(0, runBlocking { store.activeProfile.first() })
        shot("empty-deploy")
    }

    @Test fun scalesAndThemesKeepActionsVisible() {
        openDeploy()
        for (theme in listOf("dark", "light")) for (scale in UiScale.choices) {
            runBlocking { store.saveThemeMode(theme); store.saveUiScalePercent(scale) }
            compose.waitForIdle()
            compose.onNodeWithText("Установить").assertIsDisplayed()
            compose.onNodeWithText("Удалить").assertIsDisplayed()
            // Compare physical pixels: each scaled node has a different local dp conversion.
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val actions = compose.onNodeWithTag("deploy-actions").fetchSemanticsNode().boundsInRoot
            assertTrue(actions.right <= root.right && actions.left >= root.left)
            if (scale == 100) {
                val range = compose.onNodeWithTag("deploy-settings").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals("Default form should fit", 0f, range.maxValue(), 1f)
                val card = compose.onNodeWithTag("deploy-card").fetchSemanticsNode().boundsInRoot
                val density = compose.activity.resources.displayMetrics.density
                assertTrue("Free height belongs inside the form, not above the actions",
                    actions.top - card.bottom in -1f..(8f * density))
            }
            shot("deploy-$theme-$scale")
        }
        runBlocking { store.saveUiScalePercent(100) }
        compose.onNodeWithText("Подкл.").performClick()
        shot("connection")
    }
    @Test fun delayedToggleSaveCannotUndoNewerTaps() {
        openDeploy()
        val initial = runBlocking { deploy.deploySettingsSnapshot.first() }
        for (label in listOf("Ключи SSH", "Ручные порты")) {
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(label).performClick()
        }
        // An older queued save arrives after the user switched both controls off.
        runBlocking { deploy.saveDeployProfileDraft(initial.copy(sshKeysMode = true, manualPortsEnabled = true)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Порт туннеля").assertDoesNotExist()
        compose.onNodeWithText("Настроить ключ").assertDoesNotExist()
        compose.waitUntil(5000) { runBlocking { deploy.deploySettingsSnapshot.first().let { !it.manualPortsEnabled && !it.sshKeysMode } } }
        compose.onNodeWithText("Ключи SSH").performClick()
        compose.onNodeWithText("Настроить ключ").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertExists()
    }

}

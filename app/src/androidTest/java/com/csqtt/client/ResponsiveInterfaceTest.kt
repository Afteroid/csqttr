package com.csqtt.client

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.navigation.CsqttV2Root
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Emulator fixtures only; no VPN/SSH/deploy actions. */
class ResponsiveInterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: SettingsStore
    private val reservedHeight = mutableStateOf(0.dp)
    private val previewFontScale = mutableStateOf(1f)

    @Before fun setup() {
        store = SettingsStore(compose.activity)
        runBlocking {
            store.saveActiveProfile(0)
            store.saveUiOwnerMode(true)
            store.saveThemeMode("dark")
            store.saveUiDesign(UiDesignPresets.ORIGINAL_250)
            store.saveUpdateCheckIntervalHours(UPDATE_CHECK_NEVER)
            store.saveProfileConnectionSettings(0, "203.0.113.10", 46000, "FixturePassword", 70)
            store.saveVkAuthMode("vkcalls", forProfile = 0)
            store.saveVkHashMode("manual", forProfile = 0)
            store.saveExtraWorkers(false, forProfile = 0)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, previewFontScale.value)) {
                Box(Modifier.fillMaxSize().padding(bottom = reservedHeight.value)) { CsqttV2Root(store) }
            }
        }
    }

    @After fun cleanup() {
        TunnelManager.running.value = false
        TunnelManager.stopping.value = false
        TunnelManager.autoPausedForWifi.value = false
        TunnelManager.traffic.value = TunnelTraffic()
        TunnelManager.uptimeSeconds.value = null
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun cardColor(tag: String): androidx.compose.ui.graphics.Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, 3]
    }

    @Test fun trafficPreviewWorksWithoutLogging() {
        runBlocking { store.saveLoggingEnabled(false) }
        val fixture = TunnelTraffic(11_618_222, 8_095_007, 437.0, 361.0, 1000)
        compose.runOnIdle {
            TunnelManager.traffic.value = fixture
            TunnelManager.uptimeSeconds.value = 203
            TunnelManager.running.value = true
        }
        for (theme in listOf("dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            compose.onNodeWithText(TunnelTrafficText.speed(fixture)).assertIsDisplayed()
            compose.onNodeWithText(TunnelTrafficText.total(fixture)).assertIsDisplayed()
            screenshot("traffic-connection-$theme.png")
        }
        compose.runOnIdle { TunnelManager.stopping.value = true }
        compose.onNodeWithText(TunnelTrafficText.speed(fixture)).assertDoesNotExist()
        compose.runOnIdle {
            TunnelManager.stopping.value = false
            TunnelManager.autoPausedForWifi.value = true
        }
        compose.onNodeWithText(TunnelTrafficText.speed(fixture)).assertDoesNotExist()
        compose.runOnIdle {
            TunnelManager.autoPausedForWifi.value = false
            TunnelManager.running.value = false
        }
        compose.onNodeWithText(TunnelTrafficText.speed(fixture)).assertDoesNotExist()
        compose.runOnIdle { TunnelManager.running.value = true }
        compose.onNodeWithText(TunnelTrafficText.speed(fixture)).assertIsDisplayed()
        val context = compose.activity
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("traffic_preview", "CSQTT: пример трафика", android.app.NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(context, 101,
            android.content.Intent(context, TunnelService::class.java).setAction("STOP"),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = androidx.core.app.NotificationCompat.Builder(context, "traffic_preview")
            .setContentTitle("CSQTT").setSmallIcon(R.drawable.ic_stat_c)
            .withTunnelText(TunnelTrafficText.notification(fixture, 12))
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .addAction(R.drawable.ic_stop, "Отключить", stop).build()
        assertEquals(TunnelTrafficText.speed(fixture), notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals("Туннель активен · Потоков: 12", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains(TunnelTrafficText.total(fixture)))
        manager.notify(12001, notification)
    }

    @Test fun deployActionsStayVisibleWithLongNameAndSystemInsets() {
        val deployStore = SettingsStore(compose.activity, deployScope = true)
        runBlocking {
            deployStore.saveDeploy("203.0.113.10")
            deployStore.saveDeployServerName("Финляндия nodehost")
        }
        // RMX5079: (107 + 132) px / 3 = 79.7 dp system bars; emulator already uses 48 dp.
        compose.runOnIdle { reservedHeight.value = 32.dp }
        compose.onNodeWithText("Деплой").performClick()
        compose.onNodeWithText("Финляндия nodehost").assertIsDisplayed()
        for (theme in listOf("dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            for (title in listOf("Установить", "Переустановить", "Веб-панель", "Удалить с сервера")) {
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(title).assert(hasAnyAncestor(hasTestTag("deploy-settings")).not())
            }
            val scroll = compose.onNodeWithTag("deploy-settings").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            screenshot("deploy-insets-$theme.png")
            assertTrue("Deploy form overflow with phone insets: ${scroll.maxValue()}", scroll.maxValue() <= 1f)
        }
        // Even where the form genuinely needs scrolling, the actions must stay accessible.
        for (reserved in listOf(0.dp, 64.dp, 160.dp)) {
            for (scale in listOf(1f, 1.3f)) {
                compose.runOnIdle {
                    reservedHeight.value = reserved
                    previewFontScale.value = scale
                }
                compose.onNodeWithText("Удалить с сервера").assertIsDisplayed()
                compose.onNodeWithText("Переустановить").assertIsDisplayed()
            }
        }
    }

    @Test fun extraSwitchRespondsWhileConnectedAndPersistsForNextConnection() {
        compose.runOnIdle { TunnelManager.running.value = true }
        compose.onNodeWithContentDescription("Настройки").performClick()
        val wifiLeft = compose.onNodeWithText(WifiCopy.TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot().left
        val logsLeft = compose.onNodeWithText("Ведение логов", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        assertEquals("Wi-Fi row should align with the adjacent settings", logsLeft, wifiLeft)
        screenshot("responsive-settings.png")
        compose.onNodeWithText("Экстра потоки").performClick()
        compose.waitUntil(5000) { runBlocking { store.extraWorkers.first() } }
        assertTrue(TunnelManager.running.value)
        assertEquals(100, runBlocking { store.workersMaximum.first() })
        compose.onNodeWithText("Экстра потоки").performClick()
        compose.waitUntil(5000) { runBlocking { !store.extraWorkers.first() } }
        assertEquals(70, runBlocking { store.workersMaximum.first() })
    }

    @Test fun mainFormsFitAndRemovedDesignCannotBeSelected() {
        runBlocking {
            store.saveProfileParticipantMode(0, false)
            store.saveVkAuthMode("auto_js", forProfile = 0)
            store.saveVkHashMode("auto_js", forProfile = 0)
        }
        val overflow = mutableListOf<String>()
        for (theme in listOf("dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            compose.onNodeWithText("Туннель").performClick()
            compose.onNodeWithText("TURN подсети").assertIsDisplayed()
            for (label in listOf("Режим хешей", "Режим кредов", "Режим SRTP", "Транспорт", "Потоки", "TURN подсети")) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertEquals("Actual rendered weight: $label", FontWeight.Normal, results.single().layoutInput.style.fontWeight)
                assertEquals("Actual rendered size: $label", 15.sp, results.single().layoutInput.style.fontSize)
            }
            compose.onAllNodesWithText("Авто ВК", useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                assertEquals(FontWeight.Normal, results.single().layoutInput.style.fontWeight)
            }
            val tunnelScroll = compose.onNodeWithTag("tunnel-settings").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (tunnelScroll.maxValue() > 1f) overflow += "Tunnel $theme overflow: ${tunnelScroll.maxValue()}"
            screenshot("responsive-tunnel-$theme.png")
            val cardBackground = cardColor("tunnel-card")
            compose.onNodeWithText("Деплой").performClick()
            screenshot("responsive-deploy-$theme.png")
            compose.onNodeWithText("Удалить с сервера").assertIsDisplayed()
            assertEquals(cardBackground, cardColor("deploy-card"))
            val deployScroll = compose.onNodeWithTag("deploy-settings").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (deployScroll.maxValue() > 1f) overflow += "Deploy $theme overflow: ${deployScroll.maxValue()}"
            screenshot("responsive-deploy-$theme.png")
            compose.onNodeWithText("Маршрут").performClick()
            compose.onNodeWithText("Импорт и экспорт правил").assertIsDisplayed()
            screenshot("responsive-routing-$theme.png")
            assertEquals(cardBackground, cardColor("routing-card-APP"))
            compose.onNodeWithText("Логи").performClick()
            assertEquals(cardBackground, cardColor("logs-card"))
            screenshot("responsive-logs-$theme.png")
            compose.onNodeWithText("Подкл.").performClick()
            assertEquals(cardBackground, cardColor("servers-card"))
            screenshot("responsive-connection-$theme.png")
            compose.onNodeWithContentDescription("Настройки").performClick()
            screenshot("responsive-settings-$theme.png")
            compose.onNodeWithContentDescription("Назад").performClick()
        }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Новый · 2.5.0").performScrollTo().performClick()
        compose.onNodeWithText("Новый", substring = false).assertDoesNotExist()
        screenshot("responsive-design-selector.png")
        assertTrue(overflow.joinToString(), overflow.isEmpty())
    }
}

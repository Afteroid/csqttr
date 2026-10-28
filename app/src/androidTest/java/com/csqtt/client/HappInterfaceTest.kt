package com.csqtt.client

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.csqtt.client.ui.v2.navigation.CsqttV2Root
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import com.csqtt.client.ui.legacy.utils.formatCsqttLink
import com.csqtt.client.ui.v2.screens.connection.saveImportedLink
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation and persistent native settings only. Never starts SSH, deploy, or VPN. */
@RunWith(AndroidJUnit4::class)
class HappInterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: SettingsStore

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before fun showApp() {
        store = SettingsStore(compose.activity)
        runBlocking {
            store.saveUiOwnerMode(false)
            store.saveThemeMode("dark")
            store.saveUpdateCheckIntervalHours(UPDATE_CHECK_NEVER)
            store.saveActiveProfile(0)
            store.saveParticipantShowLogs(true)
            store.saveLoggingEnabled(true)
            store.saveProfileConnectionSettings(0, "203.0.113.10", 46000, "DemoPassword1234", 20)
        }
        compose.setContent { CsqttV2Root(store) }
        compose.waitForIdle()
    }

    @Test fun profileListStaysTheSameWhenThemeChanges() {
        runBlocking {
            store.saveProfileName(0, "Theme server A")
            store.saveProfileConnectionSettings(28, "203.0.113.28", 46000, "FixturePassword28", 10)
            store.saveProfileName(28, "Theme server B")
        }
        for (theme in listOf("light", "dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            compose.waitForIdle()
            compose.onNodeWithText("Theme server A").assertExists()
            compose.onNodeWithText("Theme server B").assertExists()
            screenshot("review4-connection-$theme.png")
        }
        runBlocking { store.clearProfile(28); store.saveProfileName(0, ""); store.saveThemeMode("dark") }
    }

    @Test fun profileAndTurnPingResultsSurviveNavigationAndReopeningDialogs() {
        PingResultsCache.saveProfile(0, ProfilePingResult.Success(37))
        PingResultsCache.saveTurn(TurnSubnetRegistry.subnets.first().id, 51)
        compose.onNodeWithText("37 мс").assertExists()
        repeat(2) {
            compose.onNodeWithText("Туннель").performClick()
            compose.onNodeWithText("TURN подсети").performClick()
            compose.onNodeWithText("51 мс").assertExists()
            compose.onNodeWithContentDescription("Закрыть").performClick()
            compose.onNodeWithText("Подкл.").performClick()
            compose.onNodeWithText("37 мс").assertExists()
        }
        PingResultsCache.removeProfile(0)
        PingResultsCache.clearTurn()
    }

    @Test fun participantCanOpenTunnelAndHelpWithoutDeployTab() {
        compose.onNodeWithText("Деплой").assertDoesNotExist()
        compose.onNodeWithText("Туннель").performClick()
        compose.onNodeWithText("Пароль туннеля").assertIsDisplayed()
        compose.onNodeWithText("Потоки").assertExists()
        compose.onNodeWithContentDescription("Подсказка: Режим SRTP").performClick()
        compose.onNodeWithText("Понятно").assertIsDisplayed().performClick()
        screenshot("happ-tunnel.png")
    }

    @Test fun participantCanHideLogsWithoutDisablingRecordingAndOwnerKeepsLogs() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Показывать вкладку «Логи»").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { !store.participantShowLogs.first() } }
        runBlocking { check(store.loggingEnabled.first()) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Логи").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Показывать вкладку «Логи»").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { store.participantShowLogs.first() } }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Логи").performClick()
        runBlocking { store.saveParticipantShowLogs(false) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Логи").fetchSemanticsNodes().isEmpty() }
        runBlocking { store.saveUiOwnerMode(true) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Логи").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Показывать вкладку «Логи»").assertDoesNotExist()
    }

    @Test fun captchaControlsPersistAndKeepOtherProfilesUnchanged() {
        runBlocking {
            store.saveVkAuthMode("legacy", forProfile = 0)
            store.saveCaptchaSettings("wv", "manual", forProfile = 0)
            store.saveCaptchaSettings("auto", "auto", forProfile = 26)
        }
        compose.onNodeWithText("Туннель").performClick()
        for (theme in listOf("dark", "light")) {
            runBlocking { store.saveThemeMode(theme) }
            compose.waitForIdle()
            listOf("Адрес сервера", "Пароль туннеля", "Режим хешей", "Режим кредов", "Настройки капчи", "Режим SRTP", "Транспорт", "Потоки", "TURN подсети")
                .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            val range = compose.onNodeWithTag("tunnel-settings").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            screenshot("review8-tunnel-$theme.png")
            check(range.maxValue() == 0f) { "Tunnel settings need scrolling: ${range.maxValue()}" }
        }
        compose.onNodeWithText("Настройки капчи").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("WBV").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("WBV").performClick()
        compose.onNodeWithText("RJS").performClick()
        compose.waitUntil(5000) { runBlocking { store.captchaMode.first() == "rjs" } }
        runBlocking { check(store.captchaSolveMethod.first() == "auto") }
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithText("Настройки капчи").performScrollTo().performClick()
        compose.onNodeWithText("RJS").assertExists()
        runBlocking {
            val other = SettingsStore(compose.activity, readOnlyProfile = 26)
            check(other.captchaMode.first() == "auto")
            check(other.captchaWbvSolveMethod.first() == "auto")
            store.saveVkAuthMode("vkcalls", forProfile = 0)
        }
    }

    @Test fun resourceMonitorAndVersionSheetAreReachableFromSettings() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Монитор ресурсов").performScrollTo().performClick()
        compose.onNodeWithText("Замер ресурсов").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.onNodeWithText("Версия и обновления").performScrollTo().performClick()
        compose.onNodeWithText("У вас установлена").assertExists()
    }

    @Test fun panelPathConfirmationIsReachableWithoutStartingServerWork() {
        runBlocking {
            store.saveUiOwnerMode(true)
            val deploy = SettingsStore(compose.activity, deployScope = true)
            deploy.saveDeployProfileDraft(DeploySettingsSnapshot(profile = 25, serverName = "Panel fixture",
                ip = "203.0.113.125", sshLogin = "fixture", sshPassword = "fixture-password", sshPort = "5052"))
            deploy.saveActiveProfile(25)
        }
        compose.onNodeWithText("Деплой").performClick()
        compose.onNodeWithContentDescription("Действия веб-панели").performClick()
        compose.onNodeWithText("Сменить путь панели").performClick()
        compose.onNodeWithText("Смена пути панели").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        runBlocking { check(!DeployManager.isDeploying.value) }
    }

    @Test fun connectedVkAccountOpensSessionControlsWithoutStartingLogin() {
        runBlocking {
            store.saveVkAccessToken("fixture-session-token-never-sent", "fixture-user")
            store.saveVkHashMode("auto_api", forProfile = 0)
        }
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Аккаунт VK подключён").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Аккаунт VK подключён").performClick()
        compose.onNodeWithText("Копировать токен").assertIsDisplayed()
        compose.onNodeWithText("Удалить токен").assertIsDisplayed()
        compose.onNodeWithText("Назад").performClick()
        runBlocking { store.clearVkAccessToken(); store.saveVkHashMode("manual", forProfile = 0) }
    }

    @Test fun ownerModeRevealsRealDeployAndPersistsSelection() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Участник").performClick()
        compose.onNodeWithText("Владелец").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Деплой").performClick()
        compose.onNodeWithText("IP сервера").assertIsDisplayed()
        compose.onNodeWithText("Порт SSH").assertIsDisplayed()
        compose.onNodeWithText("Установка в Docker").assertExists()
        compose.onNodeWithText("Проверить VPS").assertDoesNotExist()
        compose.onNodeWithText("Логин веб-панели").assertIsDisplayed()
        compose.onNodeWithText("Пароль веб-панели").assertIsDisplayed()
        val deploy = SettingsStore(compose.activity, deployScope = true)
        runBlocking { deploy.saveDockerInstall(true) }
        compose.waitUntil(5000) { compose.onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Логи").performClick()
        compose.onNodeWithText("Деплой").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithContentDescription("Сгенерировать пароль туннеля").performClick()
        compose.waitUntil(5000) { runBlocking { deploy.deploySettingsSnapshot.first { it.isLoaded }.mainPassword.matches(Regex("[A-Za-z0-9]{16}")) } }
        compose.onNodeWithText("Удалить с сервера").assertExists()
        compose.runOnIdle { runBlocking { check(store.uiOwnerMode.first()) } }
        screenshot("happ-deploy.png")
    }

    @Test fun selectingVpsAndSyncingDeployPasswordDoNotOverwriteOtherProfile() {
        val deploy = SettingsStore(compose.activity, deployScope = true)
        runBlocking {
            deploy.saveActiveProfile(2)
            deploy.saveDeploy("203.0.113.20")
            check(store.activeProfile.first() == 0)
            deploy.saveDeployedServer(2, "203.0.113.20", 46100, 46102)
            deploy.saveConnectionPassword("NewPassword12345", forProfile = 2)
            check(store.readProfileConnectionSettings(0).connectionPassword == "DemoPassword1234")
            val installed = store.readProfileConnectionSettings(2)
            check(installed.peer == "203.0.113.20" && installed.serverPeerPort == 46100)
            check(installed.connectionPassword == "NewPassword12345")
            check(store.activeProfile.first() == 0)
        }
    }

    @Test fun selectingImportedLinkShowsItsTunnelDataAndPreservesThePreviousProfile() {
        val hashes = (1..6).map { "test-hash-number-$it-123456" }
        runBlocking {
            val link = formatCsqttLink("203.0.113.41", 46200, "LinkPassword123", hashes = hashes, power = 33)
            store.saveImportedLink(1, link, "Сервер из ссылки")
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Сервер из ссылки").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сервер из ссылки").performClick()
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("203.0.113.41").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("203.0.113.41").assertIsDisplayed()
        compose.onNodeWithText("46200").assertIsDisplayed()
        runBlocking {
            check(store.activeProfile.first() == 1)
            val saved = store.readProfileConnectionSettings(1)
            check(saved.connectionPassword == "LinkPassword123")
            check(saved.workersPerHash == 30)
            check(store.vkHashes.first().isBlank())
            check(store.csqttLinkMode.first())
            check(resolveConnectionSource(store)?.hashes == hashes.joinToString(","))
            check(store.vkHashMode.first() == "manual")
        }
        compose.onNodeWithText("Подкл.").performClick()
        compose.onNodeWithText("Профиль 1").performClick()
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("203.0.113.10").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("203.0.113.10").assertIsDisplayed()
        runBlocking { check(store.readProfileConnectionSettings(0).connectionPassword == "DemoPassword1234") }
    }

    @Test fun importedOwnerHashesStayHiddenAndCombineWithParticipantHashes() {
        val owner = "owner-private-hash-1234567890"
        val own = "participant-own-hash-1234567890"
        runBlocking {
            check(store.saveImportedLink(27, formatCsqttLink("203.0.113.127", 46127, "FixturePassword", hashes = listOf(owner)), "Owner hash fixture"))
            store.saveActiveProfile(27)
            store.saveVkHashesOnly(own)
        }
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Настроить хеши").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Настроить хеши").performClick()
        compose.onNodeWithText(owner).assertDoesNotExist()
        compose.onNodeWithText("От владельца 1").assertExists()
        compose.onNodeWithText(own).assertExists()
        compose.onNodeWithContentDescription("Убрать хеш владельца 1").performClick()
        compose.waitUntil(5000) { runBlocking { owner in store.linkHashesRemoved.first() } }
        runBlocking {
            check(resolveConnectionSource(store)?.hashes == own)
            store.saveProfileConnectionSettings(27, "203.0.113.128", 46128, "ChangedPassword", 20)
            val source = checkNotNull(resolveConnectionSource(store))
            check(source.peer == "203.0.113.128:46128")
            check(source.password == "ChangedPassword")
            check(source.hashes == own)
        }
        compose.onNodeWithContentDescription("Закрыть").performClick()
        runBlocking { store.clearProfile(27); store.saveActiveProfile(0) }
    }

    @Test fun selectingTokenLinkUsesItsOwnVkAccountInTunnel() {
        runBlocking {
            store.saveVkAccessToken("fixture-shared-personal-token", "67890")
            store.saveImportedLink(3, formatCsqttLink("203.0.113.43", 46203, "TokenLinkPassword", token = "test-token-never-sent", power = 25), "Ссылка с токеном")
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Ссылка с токеном").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ссылка с токеном").performClick()
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Аккаунт VK подключён").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Аккаунт VK подключён").assertIsDisplayed()
        compose.onNodeWithText("203.0.113.43").assertIsDisplayed()
        runBlocking {
            check(store.vkHashMode.first() == "auto_api")
            check(store.vkAccessToken.first() == "test-token-never-sent")
            check(store.readProfileConnectionSettings(3).connectionPassword == "TokenLinkPassword")
            store.clearVkAccessToken()
            check(store.vkAccessToken.first() == "fixture-shared-personal-token")
            store.clearVkAccessToken()
        }
    }

    @Test fun manualHashesExposeAllSixFieldsAndSaveAnEditedValue() {
        val hashes = (1..6).map { "manual-hash-$it-1234567890" }
        runBlocking { store.saveVkHashMode("manual"); store.saveVkHashesOnly(hashes.joinToString(",")) }
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Настроить хеши").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Настроить хеши").performClick()
        hashes.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        screenshot("happ-manual-hashes.png")
        compose.onNodeWithText(hashes.first()).performTextReplacement("edited-hash-1234567890")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) { runBlocking { store.vkHashes.first().startsWith("edited-hash-1234567890,") } }
        runBlocking { check(store.vkHashes.first().split(',').drop(1) == hashes.drop(1)) }
    }

    @Test fun logsCanFilterErrorsAndRemoveSeparatorLines() {
        compose.runOnIdle {
            TunnelManager.logs.clear()
            TunnelManager.logs.add(LogEntry("deploy_fixture_error", "[ERR] Тест: SSH отклонён", isError = true, level = LogLevel.ERR))
            TunnelManager.logs.add(LogEntry("fixture_ok", "[OK] Тест: соединение готово", level = LogLevel.OK))
            TunnelManager.logs.add(LogEntry("fixture_separator", "────────────", level = LogLevel.LOG))
        }
        compose.onNodeWithText("Логи").performClick()
        compose.onNodeWithText("Все уровни ▾").performClick()
        compose.onNodeWithText("Ошибки").performClick()
        compose.onNodeWithText("Тест: SSH отклонён").assertIsDisplayed()
        compose.onNodeWithText("Тест: соединение готово").assertDoesNotExist()
        compose.onNodeWithText("────────────").assertDoesNotExist()
        screenshot("ui-logs-review3.png")
        compose.onNodeWithText("Ошибки ▾").performClick()
        compose.onNodeWithText("Все уровни").performClick()
        compose.onNodeWithText("Тест: соединение готово").assertIsDisplayed()
    }

    @Test fun vpsEditorShowsPortsAndFormatHintsWithoutConnectionWorkers() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Участник").performClick()
        compose.onNodeWithText("Владелец").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Деплой").performClick()
        compose.onNodeWithContentDescription("Добавить VPS").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Новый VPS").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("203.0.113.10 или vpn.example.com").onLast().assertIsDisplayed()
        compose.onNodeWithText("Потоки").assertDoesNotExist()
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        screenshot("ui-vps-editor-review3.png")
        compose.onNodeWithText("Отмена").performClick()
    }

    @Test fun twoVpsProfilesCanBeAddedAndSelectedWithoutMixingTheirConnectionData() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Участник").performClick()
        compose.onNodeWithText("Владелец").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Деплой").performClick()
        val deploy = SettingsStore(compose.activity, deployScope = true)
        fun add(name: String, ip: String, ssh: String, peer: String, web: String): Int {
            compose.onNodeWithContentDescription("Добавить VPS").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Новый VPS").fetchSemanticsNodes().isNotEmpty() }
            fun edit(label: String, value: String) { compose.onAllNodesWithContentDescription(label).onLast().performTextReplacement(value) }
            edit("Название сервера", name)
            edit("IP сервера", ip)
            edit("Порт SSH", ssh)
            edit("Логин SSH", "test-user")
            edit("Пароль SSH", "fixture-password")
            edit("Порт PEER", peer)
            edit("Порт WEB", web)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Новый VPS").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(5000) { runBlocking { deploy.deploySettingsSnapshot.first { it.isLoaded }.ip == ip } }
            return runBlocking { deploy.activeProfile.first() }
        }
        val suffix = System.nanoTime().toString().takeLast(6)
        val nameA = "VPS A $suffix"
        val nameB = "VPS B $suffix"
        val a = add(nameA, "203.0.113.81", "5052", "48100", "48102")
        val b = add(nameB, "203.0.113.82", "5053", "48200", "48202")
        check(a != b)
        compose.onNodeWithText(nameB).performClick()
        compose.onNodeWithText(nameA).performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { deploy.activeProfile.first() == a } }
        compose.waitUntil(5000) { runBlocking { store.activeProfile.first() == deploy.linkedConnectionProfile(a) } }
        compose.onNodeWithContentDescription("IP сервера").assertTextEquals("203.0.113.81")
        compose.onNodeWithContentDescription("Порт SSH").assertTextEquals("5052")
        runBlocking {
            val savedA = SettingsStore(compose.activity, readOnlyProfile = a).deploySettingsSnapshot.first { it.isLoaded }
            val savedB = SettingsStore(compose.activity, readOnlyProfile = b).deploySettingsSnapshot.first { it.isLoaded }
            check(savedA.serverPeerPort == 48100 && savedA.serverWebPort == 48102)
            check(savedA.mainPassword.isNotBlank() && savedA.webPassword.isNotBlank() && savedA.webLogin == "admin")
            check(savedB.ip == "203.0.113.82" && savedB.sshPort == "5053")
            check(savedB.serverPeerPort == 48200 && savedB.serverWebPort == 48202)
            val linkedA = deploy.linkedConnectionProfile(a)
            check(store.activeProfile.first() == linkedA)
            check(store.readProfileConnectionSettings(linkedA).peer == savedA.ip)
            check(store.profileNames.first()[linkedA] == nameA)
        }
        compose.onNodeWithText("Логи").performClick()
        // A connection cursor can differ from the VPS cursor when the tab is remounted.
        runBlocking { store.saveActiveProfile(deploy.linkedConnectionProfile(b)) }
        compose.onNodeWithText("Деплой").performClick()
        compose.onNodeWithContentDescription("IP сервера").assertTextEquals("203.0.113.81")
        compose.onNodeWithText(nameA).assertExists()
        compose.onNodeWithText(nameA).performClick()
        compose.onNodeWithText(nameB).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("IP сервера") and hasText("203.0.113.82")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("IP сервера").assertTextEquals("203.0.113.82")
        compose.onNodeWithText("Логи").performClick()
        compose.onNodeWithText("Деплой").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("IP сервера") and hasText("203.0.113.82")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("IP сервера").assertTextEquals("203.0.113.82")
        compose.onNodeWithContentDescription("Редактировать VPS").performClick()
        compose.onNodeWithText("Данные VPS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Название сервера").performTextReplacement("$nameB renamed")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Данные VPS").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Логи").performClick()
        compose.onNodeWithText("Деплой").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("$nameB renamed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("$nameB renamed").assertExists()
        runBlocking {
            check(deploy.deployServers.first().any { it.profile == a && it.name == nameA && it.ip == "203.0.113.81" })
            check(deploy.deployServers.first().any { it.profile == b && it.name == "$nameB renamed" && it.ip == "203.0.113.82" })
        }
    }
}

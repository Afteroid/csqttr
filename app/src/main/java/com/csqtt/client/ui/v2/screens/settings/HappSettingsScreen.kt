package com.csqtt.client.ui.v2.screens.settings

import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2TextSizes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2Theme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.BatterySaver
import com.csqtt.client.ui.v2.components.*
import com.csqtt.client.ui.v2.dialogs.V2ImportExportDialog
import com.csqtt.client.ui.v2.dialogs.V2WifiNetworkListDialog
import kotlinx.coroutines.launch

@Composable
fun HappSettingsScreen(
    settingsStore: SettingsStore, version: String, onBack: () -> Unit, onAbout: () -> Unit,
    onOpenResourceMonitor: () -> Unit = {}, onVersionClick: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val owner by settingsStore.uiOwnerMode.collectAsStateWithLifecycle(false)
    val extra by settingsStore.extraWorkers.collectAsStateWithLifecycle(false)
    val wifi by settingsStore.autoPauseOnWifi.collectAsStateWithLifecycle(false)
    val showLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(false)
    val profile by settingsStore.activeProfile.collectAsStateWithLifecycle(0)
    val authMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle("vkcalls")
    val theme by settingsStore.themeMode.collectAsStateWithLifecycle("dark")
    val uiScalePercent by settingsStore.uiScalePercent.collectAsStateWithLifecycle(100)
    val fingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle("chrome")
    val batteryMode by settingsStore.batterySaverMode.collectAsStateWithLifecycle(BatterySaver.DEFAULT_MODE)
    val batteryPercent by settingsStore.batterySaverPercent.collectAsStateWithLifecycle(BatterySaver.DEFAULT_PERCENT)
    var transfer by remember { mutableStateOf(false) }
    var wifiLists by remember { mutableStateOf(false) }
    var logsHelp by remember { mutableStateOf(false) }
    var extraHelp by remember { mutableStateOf(false) }
    V2AdaptiveScreen(Modifier.fillMaxSize().padding(horizontal = V2Sizes.ScreenPadding)) {
        val layout = LocalV2AdaptiveMetrics.current
        V2TopBar(title = "Настройки", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = layout.gap), verticalArrangement = Arrangement.spacedBy(layout.gap)) {
            V2Card {
                Text("CSQTTR", style = MaterialTheme.typography.titleLarge)
                Text(version, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Режим приложения", Modifier.weight(1f))
                    HappHelp("Режим приложения", "Участник подключается по профилю. Владелец дополнительно видит вкладку «Деплой». По умолчанию выбран участник.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Участник", true to "Владелец").forEach { (value, label) ->
                        Surface(onClick = { scope.launch { settingsStore.saveUiOwnerMode(value) } }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp), color = if(owner == value) V2Theme.colors.accentSoft else V2Theme.colors.field) {
                            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = V2TextSizes.Action, color = if(owner == value) V2Theme.colors.accent else V2Theme.colors.textSecondary)
                            }
                        }
                    }
                }
            }
            V2Card(color = V2Theme.colors.surface, contentPadding = PaddingValues(12.dp)) {
                V2SwitchSetting("Экстра потоки", extra, { scope.launch { settingsStore.saveExtraWorkers(it, forProfile = profile) } },
                    enabled = authMode != "auto_js", onInfoClick = { extraHelp = true })
                V2SettingRow(title = com.csqtt.client.WifiCopy.TITLE, onClick = { wifiLists = true }) {
                    Text(if (wifi) "Включена" else "Выключена", color = V2Theme.colors.textSecondary,
                        fontSize = V2TextSizes.Secondary, maxLines = 1)
                    Text("›", color = V2Theme.colors.textSecondary, fontSize = 22.sp)
                }
                V2SwitchSetting("Показывать вкладку «Логи»", showLogs, { value ->
                    scope.launch { settingsStore.saveParticipantShowLogs(value, forProfile = profile) }
                }, onInfoClick = { logsHelp = true })
            }
            V2Card(color = V2Theme.colors.surface, contentPadding = PaddingValues(12.dp)) {
                HappChoice("Масштаб интерфейса", "$uiScalePercent%", com.csqtt.client.UiScale.choices.map { "$it%" }, { label ->
                    scope.launch { settingsStore.saveUiScalePercent(label.removeSuffix("%").toInt()) }
                })
                HappChoice("Оформление", when(theme) { "light" -> "Светлое"; "dark" -> "Тёмное"; else -> "Системное" }, listOf("Тёмное", "Светлое", "Системное"), { scope.launch { settingsStore.saveThemeMode(when(it) { "Тёмное" -> "dark"; "Светлое" -> "light"; else -> "system" }) } })
                HappChoice("Отпечаток TLS", fingerprint, listOf("chrome", "firefox", "safari", "edge", "opera"), { scope.launch { settingsStore.saveFingerprint(it, forProfile = profile) } })
                HappActionRow("Импорт / экспорт", onClick = { transfer = true })
                HappActionRow("Монитор ресурсов", onClick = onOpenResourceMonitor)
                HappActionRow("Версия и обновления", onClick = onVersionClick)
                HappActionRow("О приложении", onClick = onAbout)
            }
            V2Card(color = V2Theme.colors.surface, contentPadding = PaddingValues(12.dp)) {
                HappChoice(BatterySaver.TITLE, BatterySaver.MODES.first { it.first == batteryMode }.second,
                    BatterySaver.MODES.map { it.second }, { label -> scope.launch { settingsStore.saveBatterySaverMode(BatterySaver.MODES.first { it.second == label }.first) } },
                    help = BatterySaver.DESCRIPTION)
                HappChoice(BatterySaver.LEVEL_TITLE, "≤ $batteryPercent %", BatterySaver.PERCENT_CHOICES.map { "≤ $it %" },
                    { label -> label.filter(Char::isDigit).toIntOrNull()?.let { scope.launch { settingsStore.saveBatterySaverPercent(it) } } }, enabled = batteryMode == BatterySaver.MODE_LEVEL)
            }
        }
    }
    if (extraHelp) com.csqtt.client.ui.v2.dialogs.V2ExtraWorkersInfoDialog { extraHelp = false }
    if (transfer) V2ImportExportDialog(settingsStore, { transfer = false })
    if (wifiLists) V2WifiNetworkListDialog(settingsStore, { wifiLists = false })
    if (logsHelp) AlertDialog(onDismissRequest = { logsHelp = false },
        title = { Text("Вкладка «Логи»") },
        text = { Text("Показывает или скрывает вкладку для текущего профиля. Запись событий продолжается и при скрытой вкладке.") },
        confirmButton = { TextButton(onClick = { logsHelp = false }) { Text("Понятно") } })
}

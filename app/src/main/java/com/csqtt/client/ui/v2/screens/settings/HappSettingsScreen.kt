package com.csqtt.client.ui.v2.screens.settings

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
import com.csqtt.client.BatterySaver
import com.csqtt.client.ui.v2.components.*
import com.csqtt.client.ui.v2.dialogs.V2ImportExportDialog
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
    val logs by settingsStore.loggingEnabled.collectAsStateWithLifecycle(true)
    val showLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(false)
    val profile by settingsStore.activeProfile.collectAsStateWithLifecycle(0)
    val authMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle("vkcalls")
    val running by com.csqtt.client.TunnelManager.running.collectAsStateWithLifecycle()
    val theme by settingsStore.themeMode.collectAsStateWithLifecycle("system")
    val fingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle("chrome")
    val design by settingsStore.uiDesign.collectAsStateWithLifecycle("v2")
    val batteryMode by settingsStore.batterySaverMode.collectAsStateWithLifecycle(BatterySaver.DEFAULT_MODE)
    val batteryPercent by settingsStore.batterySaverPercent.collectAsStateWithLifecycle(BatterySaver.DEFAULT_PERCENT)
    var transfer by remember { mutableStateOf(false) }
    var logsHelp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        V2TopBar(title = "Настройки", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                Text(label, fontSize = 14.sp, color = if(owner == value) V2Theme.colors.accent else V2Theme.colors.textSecondary)
                            }
                        }
                    }
                }
            }
            V2Card(contentPadding = PaddingValues(12.dp)) {
                if (authMode != "auto_js") V2SwitchSetting("Экстра потоки", extra, { scope.launch { settingsStore.saveExtraWorkers(it, forProfile = profile) } }, enabled = !running)
                V2SwitchSetting("Автопауза Wi-Fi", wifi, { scope.launch { settingsStore.saveAutoPauseOnWifi(it) } })
                V2SwitchSetting("Ведение логов", logs, { value ->
                    com.csqtt.client.TunnelManager.isLoggingEnabled = value
                    scope.launch { settingsStore.saveLoggingEnabled(value) }
                })
                if (!owner) V2SwitchSetting("Показывать вкладку «Логи»", showLogs, { value ->
                    scope.launch { settingsStore.saveParticipantShowLogs(value, forProfile = profile) }
                }, onInfoClick = { logsHelp = true })
            }
            V2Card(contentPadding = PaddingValues(12.dp)) {
                HappChoice("Дизайн", if (design == "classic") "Классический" else "Новый", listOf("Новый", "Классический"), {
                    scope.launch { settingsStore.saveUiDesign(if (it == "Классический") "classic" else "v2") }
                })
                HappChoice("Оформление", when(theme) { "light" -> "Светлое"; "dark" -> "Тёмное"; else -> "Системное" }, listOf("Тёмное", "Светлое", "Системное"), { scope.launch { settingsStore.saveThemeMode(when(it) { "Тёмное" -> "dark"; "Светлое" -> "light"; else -> "system" }) } })
                HappChoice("Отпечаток TLS", fingerprint, listOf("chrome", "firefox", "safari", "edge", "opera"), { scope.launch { settingsStore.saveFingerprint(it, forProfile = profile) } })
                HappActionRow("Импорт / экспорт", onClick = { transfer = true })
                HappActionRow("Монитор ресурсов", onClick = onOpenResourceMonitor)
                HappActionRow("Версия и обновления", onClick = onVersionClick)
                HappActionRow("О приложении", onClick = onAbout)
            }
            V2Card(contentPadding = PaddingValues(12.dp)) {
                HappChoice(BatterySaver.TITLE, BatterySaver.MODES.first { it.first == batteryMode }.second,
                    BatterySaver.MODES.map { it.second }, { label -> scope.launch { settingsStore.saveBatterySaverMode(BatterySaver.MODES.first { it.second == label }.first) } },
                    help = BatterySaver.DESCRIPTION)
                HappChoice(BatterySaver.LEVEL_TITLE, "≤ $batteryPercent %", BatterySaver.PERCENT_CHOICES.map { "≤ $it %" },
                    { label -> label.filter(Char::isDigit).toIntOrNull()?.let { scope.launch { settingsStore.saveBatterySaverPercent(it) } } }, enabled = batteryMode == BatterySaver.MODE_LEVEL)
            }
        }
    }
    if (transfer) V2ImportExportDialog(settingsStore, { transfer = false })
    if (logsHelp) AlertDialog(onDismissRequest = { logsHelp = false },
        title = { Text("Вкладка «Логи»") },
        text = { Text("У участника вкладку можно скрыть. Настройка сохраняется для текущего профиля и действует в обоих дизайнах. Запись событий отдельно управляется переключателем «Ведение логов».") },
        confirmButton = { TextButton(onClick = { logsHelp = false }) { Text("Понятно") } })
}

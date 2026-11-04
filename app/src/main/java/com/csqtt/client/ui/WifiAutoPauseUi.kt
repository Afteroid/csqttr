package com.csqtt.client.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.csqtt.client.*
import kotlinx.coroutines.launch

@Composable
internal fun WifiAutoPauseContent(store: SettingsStore) {
    val config by store.wifiPreferences.collectAsStateWithLifecycle(initialValue = null)
    val value = config ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val observation by WifiDiscovery.current.collectAsStateWithLifecycle()
    var showInput by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRevision++
        WifiDiscovery.refresh()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        WifiDiscovery.refresh()
    }
    val current = observation.ssid ?: remember(permissionRevision, observation) { currentWifiSsid(context) }
    val permissionGranted = remember(permissionRevision, observation) { hasWifiSsidPermission(context) }
    val hasAccess = remember(permissionRevision, observation) { hasWifiSsidAccess(context) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(WifiCopy.TITLE, Modifier.weight(1f))
        Switch(value.enabled, { enabled -> scope.launch { store.saveAutoPauseOnWifi(enabled) } })
    }
    Text("Когда ставить VPN на паузу", style = MaterialTheme.typography.titleSmall)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.selectableGroup()) {
            WifiNetworkListMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(value.mode == mode, enabled = value.enabled,
                    role = Role.RadioButton, onClick = { scope.launch { store.saveWifiNetworkListMode(mode.storedValue) } })
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value.mode == mode, onClick = null, enabled = value.enabled)
                    Text(mode.title, Modifier.padding(start = 12.dp), color = if (value.enabled)
                        MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (!hasAccess) {
        Text(if (!permissionGranted) "Чтобы определить имя текущей сети, разрешите доступ к точному местоположению."
            else "Чтобы Android передал имя Wi-Fi, включите геолокацию в настройках телефона.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            if (!hasWifiSsidPermission(context)) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }) { Text(if (!permissionGranted) "Разрешить" else "Открыть настройки геолокации") }
    }
    Text("Текущая сеть", style = MaterialTheme.typography.titleSmall)
    if (current == null) Text(if (hasAccess) "Нет доступного имени сети Wi-Fi" else "Имя сети недоступно", style = MaterialTheme.typography.bodyMedium)
    else WifiSelectionRow(current, current in value.selected, value.enabled && value.mode != WifiNetworkListMode.ALL,
        onSelect = { scope.launch { store.selectWifiNetwork(current, it) } })

    Text(WifiCopy.SAVED, style = MaterialTheme.typography.titleSmall)
    if (value.saved.isEmpty()) Text("Нет сохранённых сетей", style = MaterialTheme.typography.bodySmall)
    value.saved.sorted().forEach { name ->
        key(name) {
            WifiSelectionRow(name, name in value.selected, value.enabled && value.mode != WifiNetworkListMode.ALL,
                onSelect = { scope.launch { store.selectWifiNetwork(name, it) } }, onDelete = { deleting = name })
        }
    }
    if (current != null && current !in value.saved) {
        OutlinedButton(onClick = { scope.launch {
            if (!store.rememberWifiNetwork(current, value.mode))
                context.showRaisedToast(WifiCopy.RULE_CHANGED, Toast.LENGTH_SHORT)
        } }) { Text(if (value.mode == WifiNetworkListMode.WHITELIST) "Запомнить текущую сеть и исключить" else "Запомнить текущую сеть") }
    }
    if (!showInput) TextButton(onClick = { showInput = true }) { Text("Ввести название сети") }
    else {
        OutlinedTextField(input, { input = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Имя сети (SSID)") }, isError = error != null,
            supportingText = { error?.let { Text(it) } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showInput = false; input = ""; error = null }) { Text("Отмена") }
            TextButton(onClick = {
                val name = normalizeWifiSsid(input)
                when {
                    name == null || name.toByteArray(Charsets.UTF_8).size > 32 -> error = "Введите имя Wi-Fi длиной до 32 байт"
                    name in value.saved -> error = "Эта сеть уже сохранена"
                    else -> scope.launch {
                        if (store.rememberWifiNetwork(name, value.mode)) { showInput = false; input = "" }
                        else error = WifiCopy.RULE_CHANGED
                    }
                }
            }) { Text(WifiCopy.rememberLabel(value.mode)) }
        }
    }
    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить сеть?") },
            text = { Text("$name будет удалена из раздела «Сохранённые сети» и из выбранного списка автопаузы.") },
            confirmButton = { TextButton(onClick = { scope.launch { WifiDiscovery.acknowledge(name); store.forgetWifiNetwork(name); deleting = null } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

@Composable
private fun WifiSelectionRow(name: String, checked: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit, onDelete: (() -> Unit)? = null) {
    // Keep the saved selection, but highlight it only while the rule uses it.
    val highlighted = checked && enabled
    Surface(shape = MaterialTheme.shapes.medium, color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, null, enabled = enabled)
                Text(name, Modifier.padding(start = 10.dp).weight(1f), color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить $name", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A transient prompt only; no permanent settings shortcut on the connection screen. */
@Composable
internal fun WifiDiscoveryCard(store: SettingsStore, modifier: Modifier = Modifier) {
    val name by WifiDiscovery.candidate.collectAsStateWithLifecycle()
    val ssid = name ?: return
    val config by store.wifiPreferences.collectAsStateWithLifecycle(initialValue = null)
    val value = config ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember(ssid) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary)
                Text(WifiCopy.NEW_NETWORK, style = MaterialTheme.typography.titleSmall)
            }
            Text(WifiCopy.question(ssid))
            Text(WifiCopy.explanation(value.mode), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !saving, onClick = { WifiDiscovery.acknowledge(ssid) }) { Text(WifiCopy.NOT_NOW) }
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            if (store.rememberWifiNetwork(ssid, value.mode, requireEnabled = true)) {
                                WifiDiscovery.acknowledge(ssid)
                                context.showRaisedToast(WifiCopy.remembered(ssid, value.mode), Toast.LENGTH_SHORT)
                            } else context.showRaisedToast(WifiCopy.RULE_CHANGED, Toast.LENGTH_SHORT)
                        } finally { saving = false }
                    }
                }) { Text(WifiCopy.rememberLabel(value.mode)) }
            }
        }
    }
}

@Composable
internal fun WifiConnectionStatus(store: SettingsStore, running: Boolean, paused: Boolean, modifier: Modifier = Modifier) {
    val current by WifiDiscovery.current.collectAsStateWithLifecycle()
    val config by store.wifiPreferences.collectAsStateWithLifecycle(initialValue = null)
    val value = config ?: return
    val text = when {
        paused -> WifiCopy.paused(current.ssid)
        !running || !value.enabled || !current.connected -> return
        current.ssid == null -> "Wi-Fi · имя сети недоступно"
        shouldPauseForWifiNetwork(value.enabled, true, current.ssid, value.mode, value.selected) -> "Переход к автопаузе · ${current.ssid}"
        value.mode == WifiNetworkListMode.BLACKLIST -> "${current.ssid} · сеть не выбрана для автопаузы"
        value.mode == WifiNetworkListMode.WHITELIST -> "${current.ssid} · сеть исключена из автопаузы"
        else -> return
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

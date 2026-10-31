package com.csqtt.client.ui.legacy

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.WifiNetworkListMode
import com.csqtt.client.currentWifiSsid
import com.csqtt.client.decodeWifiSsids
import com.csqtt.client.hasWifiSsidPermission
import com.csqtt.client.normalizeWifiSsid
import com.csqtt.client.ui.legacy.components.CsqttSegmentedControl
import kotlinx.coroutines.launch

@Composable
internal fun WifiNetworkListDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storedMode by settingsStore.wifiNetworkListMode.collectAsStateWithLifecycle(
        initialValue = WifiNetworkListMode.WHITELIST.storedValue,
    )
    val storedSsids by settingsStore.wifiNetworkListSsids.collectAsStateWithLifecycle(initialValue = "")
    val mode = WifiNetworkListMode.fromStored(storedMode)
    val ssids = decodeWifiSsids(storedSsids).toList()
    var input by remember { mutableStateOf("") }
    var currentSsid by remember { mutableStateOf(currentWifiSsid(context)) }

    fun addSsid(raw: String?) {
        val ssid = normalizeWifiSsid(raw) ?: return
        scope.launch { settingsStore.saveWifiNetworkListSsids(ssids + ssid) }
        input = ""
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) currentSsid = currentWifiSsid(context)
    }

    fun requestOrRefreshCurrentWifi() {
        if (hasWifiSsidPermission(context)) {
            currentSsid = currentWifiSsid(context)
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Text(
                        text = "Wi-Fi списки",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }

                Text(
                    text = "Работает, когда включена «Автопауза при Wi-Fi». Белый список разрешает VPN только в указанных сетях; чёрный список отключает VPN только в указанных сетях.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CsqttSegmentedControl(
                    options = listOf(
                        WifiNetworkListMode.WHITELIST to "Белый список",
                        WifiNetworkListMode.BLACKLIST to "Чёрный список",
                    ),
                    selected = mode,
                    onSelected = { selected ->
                        scope.launch { settingsStore.saveWifiNetworkListMode(selected.storedValue) }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentSsid?.let { "Текущая сеть: $it" }
                            ?: if (hasWifiSsidPermission(context)) "Текущая сеть: не определена" else "Текущая сеть: нужно разрешение",
                        modifier = Modifier.weight(1f),
                    )
                    if (currentSsid != null) {
                        TextButton(
                            onClick = { addSsid(currentSsid) },
                            enabled = currentSsid !in ssids,
                        ) { Text("Добавить") }
                    } else {
                        TextButton(onClick = ::requestOrRefreshCurrentWifi) {
                            Text(if (hasWifiSsidPermission(context)) "Обновить" else "Разрешить")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Имя Wi-Fi (SSID)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { addSsid(input) }, enabled = normalizeWifiSsid(input) != null) {
                        Text("Добавить")
                    }
                }

                if (ssids.isEmpty()) {
                    Text(
                        text = if (mode == WifiNetworkListMode.WHITELIST) {
                            "Список пуст: при включённой автопаузе VPN будет ставиться на паузу во всех Wi-Fi сетях."
                        } else {
                            "Список пуст: VPN будет работать во всех Wi-Fi сетях."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ssids.forEach { ssid ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(ssid, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        settingsStore.saveWifiNetworkListSsids(ssids.filterNot { it == ssid })
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить $ssid")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

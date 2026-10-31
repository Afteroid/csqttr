package com.csqtt.client.ui.v2.dialogs

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.WifiNetworkListMode
import com.csqtt.client.currentWifiSsid
import com.csqtt.client.decodeWifiSsids
import com.csqtt.client.hasWifiSsidPermission
import com.csqtt.client.normalizeWifiSsid
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.components.V2SettingRow
import com.csqtt.client.ui.v2.components.V2SquareIconButton
import com.csqtt.client.ui.v2.components.V2TextField
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch

@Composable
fun V2WifiNetworkListDialog(
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
        scope.launch {
            settingsStore.saveWifiNetworkListSsids(ssids + ssid)
        }
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

    V2DialogContainer(
        title = "Wi-Fi списки",
        subtitle = "Правила автопаузы по имени сети",
        icon = Icons.Default.Wifi,
        onDismiss = onDismiss,
    ) {
        Text(
            text = "Работает, когда включена «Автопауза при Wi-Fi». В белом списке VPN работает только в указанных сетях; в чёрном — отключается только в указанных сетях.",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = V2BodyFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = V2Theme.colors.textSecondary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            V2PillButton(
                text = "Белый список",
                onClick = {
                    scope.launch { settingsStore.saveWifiNetworkListMode(WifiNetworkListMode.WHITELIST.storedValue) }
                },
                modifier = Modifier.weight(1f),
                style = if (mode == WifiNetworkListMode.WHITELIST) V2PillStyle.Accent else V2PillStyle.Outline,
                height = 44.dp,
            )
            V2PillButton(
                text = "Чёрный список",
                onClick = {
                    scope.launch { settingsStore.saveWifiNetworkListMode(WifiNetworkListMode.BLACKLIST.storedValue) }
                },
                modifier = Modifier.weight(1f),
                style = if (mode == WifiNetworkListMode.BLACKLIST) V2PillStyle.Accent else V2PillStyle.Outline,
                height = 44.dp,
            )
        }

        V2SettingRow(
            title = currentSsid?.let { "Текущая сеть: $it" }
                ?: if (hasWifiSsidPermission(context)) "Текущая сеть: не определена" else "Текущая сеть: нужно разрешение",
        ) {
            if (currentSsid != null) {
                V2PillButton(
                    text = "Добавить",
                    onClick = { addSsid(currentSsid) },
                    enabled = currentSsid !in ssids,
                    style = V2PillStyle.Outline,
                    height = 34.dp,
                )
            } else {
                V2PillButton(
                    text = if (hasWifiSsidPermission(context)) "Обновить" else "Разрешить",
                    onClick = ::requestOrRefreshCurrentWifi,
                    style = V2PillStyle.Outline,
                    height = 34.dp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            V2TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "Имя Wi-Fi (SSID)",
                modifier = Modifier.weight(1f),
            )
            V2SquareIconButton(
                icon = Icons.Default.Add,
                contentDescription = "Добавить Wi-Fi",
                onClick = { addSsid(input) },
                modifier = Modifier.width(44.dp),
                size = 44.dp,
            )
        }

        if (ssids.isEmpty()) {
            Text(
                text = if (mode == WifiNetworkListMode.WHITELIST) {
                    "Список пуст: при включённой автопаузе VPN будет ставиться на паузу во всех Wi-Fi сетях."
                } else {
                    "Список пуст: VPN будет работать во всех Wi-Fi сетях."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = V2BodyFamily),
                color = V2Theme.colors.textMuted,
            )
        } else {
            ssids.forEach { ssid ->
                V2SettingRow(title = ssid) {
                    V2SquareIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Удалить $ssid",
                        onClick = {
                            scope.launch {
                                settingsStore.saveWifiNetworkListSsids(ssids.filterNot { it == ssid })
                            }
                        },
                        size = 34.dp,
                        iconSize = 18.dp,
                    )
                }
            }
        }
    }
}

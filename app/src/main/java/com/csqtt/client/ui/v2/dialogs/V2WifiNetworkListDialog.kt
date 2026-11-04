package com.csqtt.client.ui.v2.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.csqtt.client.SettingsStore
import com.csqtt.client.WifiCopy
import com.csqtt.client.ui.WifiAutoPauseContent
import com.csqtt.client.ui.v2.components.V2DialogContainer
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2WifiNetworkListDialog(settingsStore: SettingsStore, onDismiss: () -> Unit) {
    val c = V2Theme.colors
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = c.accent, surface = c.surface,
        onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary, primaryContainer = c.accentSoft,
        onPrimaryContainer = c.textPrimary, outlineVariant = c.outline)) {
        V2DialogContainer(title = WifiCopy.TITLE, icon = Icons.Default.Wifi, onDismiss = onDismiss) {
            WifiAutoPauseContent(settingsStore)
        }
    }
}

package com.csqtt.client.ui.v2.dialogs

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.Alignment
import com.csqtt.client.VkAuthWebViewManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.v2.components.*
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

@Composable
internal fun V2VkAccountsDialog(
    store: SettingsStore,
    onAuthorize: (chooseAccount: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts by store.savedVkAccountIds.collectAsStateWithLifecycle<List<String>?>(null)
    val selected by store.vkAccessTokenUserId.collectAsStateWithLifecycle("")
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val starting by TunnelManager.starting.collectAsStateWithLifecycle()
    val stopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var removingId by remember { mutableStateOf<String?>(null) }
    val enabled = !running && !starting && !stopping && !saving && accounts != null
    val colors = V2Theme.colors

    V2DialogContainer(title = "Аккаунты VK", subtitle = "Личный аккаунт общий для ваших профилей", onDismiss = onDismiss) {
        if (accounts == null) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            accounts.orEmpty().forEachIndexed { index, id ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V2PillButton(
                    text = "Аккаунт ${index + 1}" + if (id == selected) " · выбран" else "",
                    onClick = {
                        saving = true
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                store.selectSavedVkAccount(id)
                                context.showRaisedToast("Аккаунт VK выбран", Toast.LENGTH_SHORT)
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                context.showRaisedToast("Не удалось выбрать аккаунт. Остановите туннель или войдите снова", Toast.LENGTH_LONG)
                            } finally { saving = false }
                        }
                    },
                    enabled = enabled,
                    style = if (id == selected) V2PillStyle.Contrast else V2PillStyle.Outline,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { removingId = id }, enabled = enabled) {
                    Icon(Icons.Outlined.Delete, "Удалить аккаунт ${index + 1}", tint = colors.error)
                }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (accounts.orEmpty().isEmpty()) Text("Сохранённых аккаунтов пока нет", color = colors.textSecondary)
            Text("Сохранённый аккаунт можно выбрать здесь. Для другого аккаунта выполните новый вход.", color = colors.textSecondary)
            Spacer(Modifier.height(12.dp))
            V2PillButton("Добавить / сменить аккаунт", onClick = { onAuthorize(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            if (!enabled && (running || starting || stopping)) Text("Для смены аккаунта остановите туннель", color = colors.textSecondary)
        }
    }
    removingId?.let { id ->
        AlertDialog(
            containerColor = colors.surface,
            onDismissRequest = { if (!saving) removingId = null },
            title = { Text("Удалить аккаунт из CSQTT?") },
            text = { Text("Профили серверов и остальные аккаунты сохранятся.") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                saving = true
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        store.removeSavedVkAccount(id)
                        VkAuthWebViewManager.clearVkSessionCookies { cleared ->
                            context.showRaisedToast(if (cleared) "Аккаунт удалён" else "Аккаунт удалён. Повторите вход в VK")
                        }
                        removingId = null
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { context.showRaisedToast("Не удалось удалить аккаунт")
                    } finally { saving = false }
                }
            }) { Text("Удалить", color = colors.error) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { removingId = null }) { Text("Отмена") } },
        )
    }

}

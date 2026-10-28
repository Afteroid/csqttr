@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.csqtt.client.ui.v2.screens.deploy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.components.*
import com.csqtt.client.ui.v2.design.V2Theme

/** VPS credentials and service ports only. Connection workers belong to the Tunnel screen. */
@Composable
fun V2ServerConfigSheet(
    serverName: String, onServerNameChange: (String) -> Unit,
    host: String, onHostChange: (String) -> Unit,
    sshLogin: String, onSshLoginChange: (String) -> Unit,
    sshPassword: String, onSshPasswordChange: (String) -> Unit,
    sshPort: String, onSshPortChange: (String) -> Unit,
    peerPort: String, onPeerPortChange: (String) -> Unit,
    webPort: String, onWebPortChange: (String) -> Unit,
    sshKeysMode: Boolean, onSshKeysModeChange: (Boolean) -> Unit,
    sshKeysFilled: Int, isDeploying: Boolean,
    onOpenSshKeys: () -> Unit, onOpenSecrets: () -> Unit,
    onSave: () -> Unit, onDeleteServer: (() -> Unit)? = null, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, connectionOnly: Boolean = false,
) {
    val c = V2Theme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val valid = host.isNotBlank() && !com.csqtt.client.ui.legacy.hostHasPort(host) && (sshPort.isBlank() || sshPort.toIntOrNull() in 1..65535) &&
        peerPort.toIntOrNull() in 1..65535 && webPort.toIntOrNull() in 1..65535
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.surface) {
        Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if(connectionOnly) "Новый VPS" else "Данные VPS", fontSize = 18.sp, color = c.textPrimary)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HappField("Название сервера", serverName, onServerNameChange, enabled = !isDeploying, placeholder = "Например, Финляндия · VPS")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("IP сервера", host, { onHostChange(it.filterNot(Char::isWhitespace)) }, Modifier.weight(1.65f), enabled = !isDeploying)
                    HappField("Порт SSH", sshPort, { onSshPortChange(it.filter(Char::isDigit).take(5)) }, Modifier.weight(1f), number = true, enabled = !isDeploying)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("Логин SSH", sshLogin, onSshLoginChange, Modifier.weight(1f), enabled = !isDeploying)
                    if(sshKeysMode) TextButton(onClick = onOpenSshKeys, enabled = !isDeploying, modifier = Modifier.weight(1f).padding(top = 16.dp)) { Text(if(sshKeysFilled > 0) "SSH-ключ задан" else "Добавить SSH-ключ") }
                    else HappField("Пароль SSH", sshPassword, onSshPasswordChange, Modifier.weight(1f), password = true, enabled = !isDeploying)
                }
                HappChoice("Способ входа", if(sshKeysMode) "SSH-ключ" else "Пароль", listOf("Пароль", "SSH-ключ"), { onSshKeysModeChange(it == "SSH-ключ") }, enabled = !isDeploying)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("Порт PEER", peerPort, { onPeerPortChange(it.filter(Char::isDigit).take(5)) }, Modifier.weight(1f), number = true, enabled = !isDeploying)
                    HappField("Порт WEB", webPort, { onWebPortChange(it.filter(Char::isDigit).take(5)) }, Modifier.weight(1f), number = true, enabled = !isDeploying)
                }
                if(!connectionOnly) HappActionRow("Доступ к веб-панели", onClick = onOpenSecrets)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if(onDeleteServer != null) OutlinedButton(onClick = onDeleteServer, enabled = !isDeploying, modifier = Modifier.weight(1f)) { Text("Удалить", color = c.error) }
                Button(onClick = { onSave() }, enabled = valid && !isDeploying, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
            }
        }
    }
}
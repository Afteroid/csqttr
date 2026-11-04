package com.csqtt.client.ui.legacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ServerRemovalRequestDialog(name: String, host: String, sshPort: String, sshReady: Boolean,
    onDismiss: () -> Unit, onContinue: () -> Unit, onConfigure: () -> Unit) {
    val ready = host.isNotBlank() && sshReady
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Удалить CSQTT с сервера?") },
        text = { Text("VPS: $name\nАдрес: ${host.ifBlank { "не указан" }} · SSH: $sshPort\n\n" +
            if (!ready) "Сначала укажите адрес и данные входа SSH." else
                "Сначала проверим установленные экземпляры CSQTT. Затем выберите экземпляр и подтвердите его удаление. До последнего подтверждения сервер не изменяется.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = if (ready) onContinue else onConfigure) {
            Text(if (ready) "Продолжить" else "Заполнить SSH")
        } })
}

@Composable
internal fun ServerRemovalProgressDialog(name: String, host: String, sshPort: String, busy: Boolean,
    error: String?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Проверка перед удалением") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$name · $host:$sshPort")
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("Подключение по SSH и поиск установленных экземпляров CSQTT…")
            } else Text(error ?: "Проверка остановлена или ожидает подтверждения SSH-ключа.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Отменить проверку" else "Закрыть") } })
}

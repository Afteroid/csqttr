package com.csqtt.client.ui.v2.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.v2.components.HappChoice
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import kotlinx.coroutines.launch

@Composable
internal fun HappCaptchaDialog(store: SettingsStore, profile: Int, busy: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scoped = remember(profile) { SettingsStore(context, readOnlyProfile = profile) }
    val mode by scoped.captchaMode.collectAsStateWithLifecycle<String?>(null)
    val wbvMethod by scoped.captchaWbvSolveMethod.collectAsStateWithLifecycle<String?>(null)
    val ready = !busy && mode != null && wbvMethod != null
    fun save(nextMode: String, nextMethod: String = wbvMethod ?: "manual") {
        scope.launch { store.saveCaptchaSettings(nextMode, nextMethod, forProfile = profile) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Настройки капчи") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Используются в режиме кредов «Капча». Настройки относятся к выбранному профилю.")
            V2SwitchSetting("Авто капча", mode == "auto", { save(if (it) "auto" else "wv") }, enabled = ready)
            if (mode != null && mode != "auto") {
                HappChoice("Метод обхода капчи", if (mode == "rjs") "RJS" else "WBV", listOf("WBV", "RJS"),
                    { save(if (it == "WBV") "wv" else "rjs") }, enabled = ready)
                HappChoice("Режим обхода", if (mode == "wv" && wbvMethod != "auto") "Ручной" else "Авто",
                    listOf("Ручной", "Авто"), { save("wv", if (it == "Ручной") "manual" else "auto") },
                    enabled = ready && mode == "wv")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } })
}

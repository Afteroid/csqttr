package com.csqtt.client.ui.v2.screens.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.defaultProfileName
import com.csqtt.client.ProfileTunnelProbe
import com.csqtt.client.ProfilePingResult
import com.csqtt.client.PingResultsCache
import com.csqtt.client.ui.v2.components.HappHelp
import com.csqtt.client.ui.v2.design.V2Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HappServerList(store: SettingsStore, active: Int, busy: Boolean, onEdit: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = V2Theme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val occupied by store.occupiedProfiles.collectAsStateWithLifecycle(emptySet())
    val names by store.profileNames.collectAsStateWithLifecycle(emptyMap())
    val servers by store.deployServers.collectAsStateWithLifecycle(emptyList())
    val latency by PingResultsCache.profileResults.collectAsStateWithLifecycle()
    val checking = remember { mutableStateMapOf<Int,Boolean>() }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    fun ping(ids: List<Int>) {
        if(busy || checkJob?.isActive == true) return
        checkJob = scope.launch {
            ids.forEach { id ->
                checking[id] = true
                PingResultsCache.removeProfile(id)
                try { PingResultsCache.saveProfile(id, ProfileTunnelProbe.check(context, id)) } finally { checking[id] = false }
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = c.surface) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы", Modifier.weight(1f), color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            HappHelp("Пинг", "Проверяется обмен данными через VK/TURN и CSQTT. Время — задержка DNS-запроса внутри туннеля. До 60 секунд на профиль. Для проверки отключите VPN. Системное подключение не переключается.")
            if (checkJob?.isActive == true) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                IconButton(onClick = { checkJob?.cancel() }, modifier = Modifier.size(36.dp)
                    .semantics { contentDescription = "Остановить проверку пинга" }) {
                    Text("×", color = c.textSecondary, fontSize = 24.sp)
                }
            }
            TextButton(onClick = { ping(occupied.sorted()) },
                enabled = occupied.isNotEmpty() && !busy && checkJob?.isActive != true) { Text("Пинг") }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if(occupied.isEmpty()) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text("Серверы пока не добавлены.\nНажмите +, чтобы добавить сервер.", color = c.textSecondary, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            occupied.sorted().forEach { id ->
                val core = servers.firstOrNull { it.profile == id }?.installedVersion?.takeIf(String::isNotBlank)?.let { "CSQTT $it" } ?: "CSQTT"
                Surface(color = if(active == id) c.accentSoft else c.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp,c.outline),
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if(!busy && checkJob?.isActive != true) scope.launch { store.saveActiveProfile(id) } }, onLongClick = { if(checkJob?.isActive != true) onEdit(id) })) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names[id] ?: defaultProfileName(id), color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((latency[id] as? ProfilePingResult.Failure)?.reason ?: "Ядро: $core", color = if(latency[id] is ProfilePingResult.Failure) c.warning else c.textSecondary, fontSize = 11.sp)
                        }
                        val result = latency[id]
                        TextButton(onClick = { ping(listOf(id)) }, enabled = !busy && checkJob?.isActive != true) { Text(if(checking[id] == true) "…" else when(result) { is ProfilePingResult.Success -> "${result.milliseconds} мс"; is ProfilePingResult.Failure -> "Ошибка"; null -> "Пинг" }, color = if(result is ProfilePingResult.Success && result.milliseconds <= 100) c.success else c.warning, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    }
}

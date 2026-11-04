package com.csqtt.client.ui.v2.screens.connection

import com.csqtt.client.ui.v2.design.V2TextSizes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.defaultProfileName
import com.csqtt.client.ProfileTunnelProbe
import com.csqtt.client.ProfilePingResult
import com.csqtt.client.PingResultsCache
import com.csqtt.client.ui.v2.components.HappHelp
import com.csqtt.client.ui.v2.components.LocalV2AdaptiveMetrics
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ProfilePingController
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HappServerList(store: SettingsStore, active: Int, busy: Boolean, onEdit: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = V2Theme.colors
    val layout = LocalV2AdaptiveMetrics.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val occupied by store.occupiedProfiles.collectAsStateWithLifecycle(emptySet())
    val names by store.profileNames.collectAsStateWithLifecycle(emptyMap())
    val servers by store.deployServers.collectAsStateWithLifecycle(emptyList())
    val latency by PingResultsCache.profileResults.collectAsStateWithLifecycle()
    val pingController = remember(scope, context) {
        ProfilePingController(scope, { ProfileTunnelProbe.check(context, it) },
            PingResultsCache::removeProfile, PingResultsCache::saveProfile)
    }
    val pingState by pingController.state.collectAsStateWithLifecycle()
    DisposableEffect(pingController) { onDispose { pingController.cancel() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { pingController.cancel() }
    LaunchedEffect(busy) { if (busy) pingController.cancel() }
    var revealedProfile by remember { mutableStateOf<Int?>(null) }
    var deletingProfile by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(busy, pingState.running) { revealedProfile = null }
    fun ping(ids: List<Int>) {
        if (!busy) pingController.start(ids)
    }
    val listBackground = c.surface
    Surface(modifier.testTag("servers-card"), shape = RoundedCornerShape(22.dp), color = listBackground) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Серверы", Modifier.weight(1f), color = c.textPrimary, fontSize = V2TextSizes.Body, fontWeight = FontWeight.Normal)
            HappHelp("Пинг", "Проверяется обмен данными через VK/TURN и CSQTT. Время — задержка DNS-запроса внутри туннеля. До 60 секунд на профиль. Для проверки отключите VPN. Системное подключение не переключается.")
            if (pingState.running) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                IconButton(onClick = { pingController.cancel() }, enabled = !pingState.stopping, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Остановить проверку пинга" }) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = c.textPrimary)
                }
            }
            TextButton(onClick = { ping(occupied.sorted()) },
                enabled = occupied.isNotEmpty() && !busy && !pingState.running) { Text(if (pingState.stopping) "Стоп…" else "Пинг") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(layout.gap)) {
            if(occupied.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text("Серверы пока не добавлены.\nНажмите +, чтобы добавить сервер.", color = c.textSecondary, fontSize = V2TextSizes.Secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } }
            items(occupied.sorted(), key = { it }) { id ->
                val core = servers.firstOrNull { it.profile == id }?.installedVersion?.takeIf(String::isNotBlank)?.let { "CSQTT $it" } ?: "CSQTT"
                ProfileSwipeActions(
                    expanded = revealedProfile == id,
                    enabled = !busy && !pingState.running,
                    onExpandedChange = { revealedProfile = if (it) id else null },
                    onEdit = { revealedProfile = null; onEdit(id) },
                    onDelete = { revealedProfile = null; deletingProfile = id },
                ) { swipeModifier ->
                Surface(color = if(active == id) c.accentSoft else c.background, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, if(active == id) c.accent else c.outline),
                    modifier = swipeModifier.fillMaxWidth().combinedClickable(onClick = { if(revealedProfile == id) revealedProfile = null else if(!busy && !pingState.running) scope.launch { store.saveActiveProfile(id) } }, onLongClick = { if(!busy && !pingState.running) onEdit(id) })) {
                    Row(Modifier.heightIn(min = 60.dp).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names[id] ?: defaultProfileName(id), color = c.textPrimary, fontSize = V2TextSizes.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((latency[id] as? ProfilePingResult.Failure)?.reason ?: "Ядро: $core", color = if(latency[id] is ProfilePingResult.Failure) c.warning else c.textSecondary, fontSize = V2TextSizes.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val result = latency[id]
                        TextButton(onClick = { ping(listOf(id)) }, enabled = !busy && !pingState.running) { Text(if(pingState.running && pingState.profile == id) "…" else when(result) { is ProfilePingResult.Success -> "${result.milliseconds} мс"; is ProfilePingResult.Failure -> "Ошибка"; null -> "Пинг" }, color = when { result == null -> c.accent; result is ProfilePingResult.Success && result.milliseconds <= 100 -> c.success; else -> c.warning }, fontSize = V2TextSizes.Action) }
                    }
                }
                }
            }
        }
    }
    }
    deletingProfile?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text("Удалить профиль?") },
            text = { Text("Профиль «${names[id] ?: defaultProfileName(id)}» будет удалён с устройства.") },
            confirmButton = {
                Button(
                    enabled = !busy && !pingState.running,
                    colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger),
                    onClick = {
                        deletingProfile = null
                        scope.launch { store.clearProfile(id) }
                    },
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text("Отмена") } },
        )
    }
}

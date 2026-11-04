package com.csqtt.client.ui.v2.screens.settings

import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2TextSizes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.csqtt.client.*
import com.csqtt.client.ui.v2.components.*
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.dialogs.V2HashesDialog
import com.csqtt.client.ui.v2.dialogs.V2TurnSubnetsDialog
import com.csqtt.client.ui.v2.dialogs.V2VkTokenRevokeDialog
import com.csqtt.client.ui.v2.dialogs.V2AutoVkRiskDialog
import com.csqtt.client.ui.v2.dialogs.V2TcpTransportRiskDialog
import com.csqtt.client.ui.v2.screens.connection.V2ProfilePickerMenu
import com.csqtt.client.ui.v2.screens.connection.V2EditProfileModal
import com.csqtt.client.ui.v2.screens.connection.ProfileSwipeActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

private val draftWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
internal fun HappTunnelScreen(settingsStore: SettingsStore, version: String, onSettings: () -> Unit, onVkAuthRequested: () -> Unit, snapshot: TunnelScreenSnapshot?, draftCache: TunnelDraftCache) {
    if (snapshot == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = V2Sizes.ScreenPadding)) { V2TopBar(version = version, title = "Туннель", onSettings = onSettings) }
        return
    }
    val c = V2Theme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile = snapshot.profile
    val editingProfile = profile
    val names by settingsStore.profileNames.collectAsStateWithLifecycle(emptyMap())
    val occupied by settingsStore.occupiedProfiles.collectAsStateWithLifecycle(emptySet())
    val subscriptions by settingsStore.profileSubscriptions.collectAsStateWithLifecycle(emptyMap())
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val starting by TunnelManager.starting.collectAsStateWithLifecycle()
    val stopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val busy = running || starting || stopping
    val participant = snapshot.connection.csqttLinkMode
    val rawLink = snapshot.connection.csqttLink
    val link = remember(rawLink) { com.csqtt.client.ui.legacy.utils.parseCsqttLink(rawLink) }
    val ownerHashes = if (participant) link?.hashes.orEmpty() else emptyList()
    val removedOwner = snapshot.removedOwnerHashes
    val linkTokenActive = participant && !link?.vkAccessToken.isNullOrBlank()
    val hashes = snapshot.hashes
    val hashMode = snapshot.hashMode
    val authMode = snapshot.authMode
    val token = snapshot.token
    val srtp = snapshot.srtp
    val transport = snapshot.transport
    val subnet = snapshot.subnet
    val ipLimit = snapshot.ipLimit
    val results = snapshot.hashResults
    val extraWorkers = snapshot.connection.extraWorkers
    val workerMaximum = WorkerCountPolicy.defaultMaximum(extraWorkers && authMode != "auto_js")
    val draft = remember(profile, snapshot.connection.revision) { draftCache.obtain(snapshot) }
    var host by draft.host
    var port by draft.port
    var password by draft.password
    var workers by draft.workers
    val revision = draft.revision
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var actionsExpanded by remember(profile) { mutableStateOf(false) }
    var confirmDelete by remember(profile) { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) actionsExpanded = false }
    var showHashes by remember { mutableStateOf(false) }
    var showSubnets by remember { mutableStateOf(false) }
    var showCaptcha by remember(profile) { mutableStateOf(false) }
    var pendingAutoJs by remember(profile) { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTcp by remember(profile) { mutableStateOf<(() -> Unit)?>(null) }
    val autoJsAcknowledged = snapshot.autoJsAcknowledged
    val tcpAcknowledged = snapshot.tcpAcknowledged
    val effectiveWorkers = WorkerCountPolicy.normalize(workers, workerMaximum)
    val draftTracker = remember(profile, revision) { mutableStateOf<ProfileConnectionSnapshot?>(null) }
    SideEffect {
        draftTracker.value = if (!busy) ProfileConnectionSnapshot(revision = revision, peer = host, serverPeerPort = port.toIntOrNull() ?: 0, connectionPassword = password, workersPerHash = effectiveWorkers) else null
    }
    DisposableEffect(profile, revision) {
        val slot = profile
        onDispose {
            val draft = draftTracker.value
            if (draft != null && draft.serverPeerPort in 1..65535) draftWrites.launch {
                settingsStore.saveProfileConnectionSettings(slot, draft.peer, draft.serverPeerPort, draft.connectionPassword, draft.workersPerHash, expectedRevision = draft.revision)
            }
        }
    }
    LaunchedEffect(profile, revision, host, port, password, effectiveWorkers) {
        if (busy) return@LaunchedEffect
        delay(350)
        val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@LaunchedEffect
        settingsStore.saveProfileConnectionSettings(profile, host, validPort, password, effectiveWorkers, expectedRevision = revision)
    }
    fun requestAutoJs(nextAuth: String, apply: () -> Unit) {
        if (shouldConfirmAutoJsMode(authMode, nextAuth, autoJsAcknowledged)) pendingAutoJs = apply else apply()
    }
    fun changeHash(label: String) {
        val mode = when(label) { "Авто API" -> "auto_api"; "Авто ВК" -> "auto_js"; else -> "manual" }
        requestAutoJs(vkAuthModeForHashMode(mode, authMode)) {
            scope.launch { settingsStore.saveVkHashMode(mode, forProfile = editingProfile) }
        }
    }
    LaunchedEffect(profile, authMode, extraWorkers, ownerHashes) {
        if (authMode == "auto_js" && ownerHashes.isNotEmpty()) settingsStore.saveVkAuthMode("vkcalls", forProfile = profile)
        if (authMode == "auto_js" && extraWorkers) settingsStore.saveExtraWorkers(false, forProfile = profile)
    }
    val parts = hashes.split(Regex("[,\\s]+" )).filter(String::isNotBlank)
    val green = c.success
    val orange = c.warning
    V2AdaptiveScreen(Modifier.fillMaxSize().padding(horizontal = V2Sizes.ScreenPadding), dense = transport != "udp") {
        val layout = LocalV2AdaptiveMetrics.current
        val pill = RoundedCornerShape(50)
        V2TopBar(version = version, title = "Туннель", onSettings = onSettings)
        Column(Modifier.weight(1f).fillMaxWidth().testTag("tunnel-settings").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(layout.gap)) {
            ProfileSwipeActions(
                expanded = actionsExpanded, enabled = !busy,
                onExpandedChange = { actionsExpanded = it },
                onEdit = { actionsExpanded = false; editing = profile },
                onDelete = { actionsExpanded = false; confirmDelete = true },
            ) { swipeModifier ->
            V2Card(modifier = swipeModifier, onClick = { if (actionsExpanded) actionsExpanded = false else if (!busy) picker = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), color = c.surface) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(snapshot.name, fontSize = V2TextSizes.Body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text("$host:$port", color = c.textSecondary, fontSize = V2TextSizes.Secondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    TextButton(onClick = { editing = profile }, enabled = !busy) { Text("Изменить", fontSize = V2TextSizes.Action) }
                }
            }
            }
            V2Card(modifier = Modifier.testTag("tunnel-card"), contentPadding = PaddingValues(layout.cardPadding), color = c.surface,
                contentModifier = Modifier.heightIn(min = (layout.height - 116.dp - layout.gap * 2).coerceAtLeast(0.dp)),
                verticalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("Адрес сервера", host, { host = it }, Modifier.weight(1.65f), enabled = !busy, compact = true, shape = pill, floatingLabel = true)
                    HappField("Порт PEER", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), number = true, enabled = !busy, compact = true, shape = pill, floatingLabel = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                HappField("Пароль туннеля", password, { password = it }, Modifier.weight(1.3f), password = true, visibilityKey = profile, enabled = !busy, compact = true, shape = pill, floatingLabel = true)
                Box(Modifier.weight(1f)) {
                if (!linkTokenActive && ownerHashes.isEmpty() && authMode != "auto_js" && hashMode == "manual" || ownerHashes.isNotEmpty()) {
                    V2PillButton("Настроить хеши", { showHashes = true }, modifier = Modifier.fillMaxWidth(), height = layout.fieldHeight, horizontalPadding = 4.dp, enabled = !busy, style = V2PillStyle.Outline, shape = pill)
                } else {
                    V2PillButton(if (linkTokenActive) "Аккаунт из ссылки" else if (token.isNotBlank()) "Аккаунт VK подключён" else "Войти в VK", onVkAuthRequested, modifier = Modifier.fillMaxWidth(), height = layout.fieldHeight, horizontalPadding = 4.dp, enabled = !busy && !linkTokenActive, style = V2PillStyle.Accent, shape = pill, maxLines = 2)
                }
                }
                }
                if (ownerHashes.isNotEmpty()) Text("Хеши владельца: ${ownerHashes.count { it !in removedOwner }} · свои: ${parts.size}", color = c.accent, fontSize = 12.sp)
                if (ownerHashes.isEmpty() && !linkTokenActive) HappChoice("Режим хешей", when(hashMode) { "auto_api" -> "Авто API"; "auto_js" -> "Авто ВК"; else -> "Ручные хеши" }, listOf("Ручные хеши", "Авто API", "Авто ВК"), ::changeHash,
                    compact = true, shape = pill, tone = if(hashMode == "auto_js") orange else green, enabled = !busy,
                    help = "Ручные хеши — шесть хешей звонков без входа в VK. Авто API — получение через API VK. Авто ВК — получение через авторизованный аккаунт VK.")
                HappChoice("Режим кредов", when(authMode) { "auto_js" -> "Авто ВК"; "legacy" -> "Капча"; else -> "Авто" }, if (ownerHashes.isNotEmpty()) listOf("Авто", "Капча") else listOf("Авто", "Капча", "Авто ВК"), { label ->
                    val mode = when(label) { "Авто ВК" -> "auto_js"; "Капча" -> "legacy"; else -> "vkcalls" }
                    requestAutoJs(mode) { scope.launch { settingsStore.saveVkAuthMode(mode, forProfile = editingProfile) } }
                },
                    compact = true, shape = pill, tone = if(authMode == "vkcalls") green else orange, enabled = !busy, help = "Авто — гостевые TURN-креды VK Calls. Капча — браузерный способ с возможной капчей. Авто ВК — креды авторизованного аккаунта.")
                if (authMode == "legacy") OutlinedButton(
                    onClick = { showCaptcha = true },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    border = BorderStroke(1.dp, c.outline),
                ) { Text("Настройки капчи", color = c.accent) }
                HappChoice("Режим SRTP", when(srtp) { "dtls-srtp" -> "DTLS-SRTP"; "wrap-srtp" -> "WRAP-SRTP"; else -> "Авто" }, listOf("Авто", "DTLS-SRTP", "WRAP-SRTP"), { label -> scope.launch { settingsStore.saveSrtpMode(when(label) { "DTLS-SRTP" -> "dtls-srtp"; "WRAP-SRTP" -> "wrap-srtp"; else -> "auto" }, forProfile = editingProfile) } },
                    compact = true, shape = pill, tone = if(srtp == "wrap-srtp") orange else green, enabled = !busy, help = "Авто — DTLS-SRTP с переходом к WRAP-SRTP. DTLS-SRTP — настоящее рукопожатие. WRAP-SRTP — SRTP-обёртка без DTLS-рукопожатия.")
                HappChoice("Транспорт", if(transport == "udp") "UDP" else "TCP", listOf("UDP", "TCP"), { label ->
                    val mode = if(label == "UDP") "udp" else CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
                    val apply = { scope.launch { settingsStore.saveTurnTransport(mode, forProfile = editingProfile) }; Unit }
                    if (shouldConfirmTcpTransport(transport, mode, tcpAcknowledged)) pendingTcp = apply else apply()
                },
                    compact = true, shape = pill, tone = if(transport == "udp") green else orange, enabled = !busy,
                    help = "UDP — основной транспорт TURN. TCP/TLS — альтернатива, если UDP недоступен. Gooby запускается вместе с туннелем. Для TURN/TCP обрабатывается начало Allocate, для TLS — ClientHello. После установки соединения данные идут без искусственных пауз.",
                    labelContent = if (transport == "udp") null else {
                        {
                            Column {
                                Text("Транспорт", fontSize = V2TextSizes.Body)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Gooby", fontSize = V2TextSizes.Body, color = c.textPrimary)
                                    Spacer(Modifier.weight(1f))
                                    V2Switch(snapshot.goobyEnabled, { enabled ->
                                        scope.launch { settingsStore.saveGoobyEnabled(enabled, forProfile = editingProfile) }
                                    }, modifier = Modifier.testTag("gooby-switch"), enabled = !busy)
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    })
                if (transport != "udp" && snapshot.goobyEnabled) HappChoice(
                    "Стратегия Gooby", GoobyPolicy.strategyLabel(snapshot.goobyStrategy), GoobyPolicy.strategies.map { it.second },
                    { label -> scope.launch { settingsStore.saveGoobyStrategy(GoobyPolicy.strategies.first { it.second == label }.first, forProfile = editingProfile) } },
                    compact = true, shape = pill, enabled = !busy, valueMaxLines = 2,
                    help = "Текущая — прежнее поведение. Мягкая — меньше дроблений и пауз, без низкого TTL. Расширенная — экспериментальное дробление с большими паузами. Обработка только в начале TCP/TLS; скорость и обход зависят от сети. Выбор применяется при следующем подключении.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Потоки", Modifier.weight(1f), fontSize = V2TextSizes.Body)
                    HappHelp("Потоки", "Число параллельных TURN-потоков. Настройка относится к текущему профилю.")
                    V2PowerSlider(effectiveWorkers.toFloat(), maximum = workerMaximum.toFloat(), enabled = !busy,
                        onValueChange = { workers = WorkerCountPolicy.normalize(it.roundToInt(), workerMaximum) }, showHeader = false,
                        modifier = Modifier.weight(2f).padding(horizontal = 6.dp))
                    Surface(color = c.accentSoft, shape = RoundedCornerShape(10.dp)) { Text(effectiveWorkers.toString(), color = c.accent, fontSize = V2TextSizes.Body, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                }
                HappActionRow("TURN подсети", subnet.ifBlank { "Автоматически" }, { showSubnets = true }, compact = true)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить профиль?") },
        text = { Text("Профиль «${snapshot.name}» будет удалён с устройства.") },
        confirmButton = { Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger), onClick = {
            confirmDelete = false
            scope.launch { settingsStore.clearProfile(profile) }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
    if (picker) Dialog(onDismissRequest = { picker = false }) { V2ProfilePickerMenu(settingsStore, profile, names, occupied, subscriptions, busy, { picker = false }, { editing = it }) }
    editing?.let { slot -> Dialog(onDismissRequest = { editing = null }) { V2EditProfileModal(settingsStore, slot, names[slot] ?: defaultProfileName(slot), isNew = false, onSaved = {}, onDismiss = { editing = null }) } }
    if (showHashes) V2HashesDialog(parts.getOrElse(0){""}, parts.getOrElse(1){""}, parts.getOrElse(2){""}, parts.getOrElse(3){""}, parts.getOrElse(4){""}, parts.getOrElse(5){""},
        VkHashValidationCodec.decode(results), { scope.launch { settingsStore.saveVkHashCheckResults(VkHashValidationCodec.encode(it)) } },
        { list -> VkHashValidator.check(context, list, settingsStore.selectedFingerprint.first(), settingsStore.activeClientIds.first()) },
        onSave = { a,b,d,e,f,g -> scope.launch { settingsStore.saveVkHashesOnly(listOf(a,b,d,e,f,g).filter(String::isNotBlank).distinct().joinToString(",")) } }, onDismiss = { showHashes = false },
        ownerHashes = ownerHashes, removedOwnerHashes = removedOwner,
        onRemovedOwnerHashesChange = { removed -> scope.launch { settingsStore.saveLinkHashesRemoved(removed, forProfile = editingProfile) } })
    if (showSubnets) V2TurnSubnetsDialog(subnet, ipLimit, { scope.launch { settingsStore.saveSelectedTurnSubnet(it, forProfile = editingProfile) } }, { scope.launch { settingsStore.saveTurnSubnetIpLimit(it, forProfile = editingProfile) } }, { showSubnets = false })
    if (showCaptcha) HappCaptchaDialog(settingsStore, profile, busy) { showCaptcha = false }
    pendingAutoJs?.let { apply -> V2AutoVkRiskDialog(true,
        onAcknowledge = { pendingAutoJs = null; apply() },
        onDoNotRemind = { scope.launch { settingsStore.saveAutoJsRiskAcknowledged(true) }; pendingAutoJs = null; apply() }) }
    pendingTcp?.let { apply -> V2TcpTransportRiskDialog(
        onAcknowledge = { pendingTcp = null; apply() },
        onDoNotRemind = { scope.launch { settingsStore.saveTcpTransportRiskAcknowledged(true) }; pendingTcp = null; apply() }) }
}

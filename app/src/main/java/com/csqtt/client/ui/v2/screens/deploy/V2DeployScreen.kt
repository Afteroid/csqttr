@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.screens.deploy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dns
import com.csqtt.client.ui.v2.components.V2DialogContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DeployManager
import com.csqtt.client.DeployServerInfo
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.SettingsStore
import com.csqtt.client.SshHostKeyStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.showRaisedToast
import com.csqtt.client.isNewerVersion
import com.csqtt.client.ui.legacy.CheckChoice
import com.csqtt.client.ui.legacy.CheckChoiceStyle
import com.csqtt.client.ui.legacy.DeployActionMode
import com.csqtt.client.ui.legacy.DeployInstance
import com.csqtt.client.ui.legacy.DeployPlan
import com.csqtt.client.ui.legacy.EffectiveServerPorts as CheckPorts
import com.csqtt.client.ui.legacy.FoundServer
import com.csqtt.client.ui.legacy.PlanAction
import com.csqtt.client.ui.legacy.PlanKind
import com.csqtt.client.ui.legacy.ServerCheckDialog
import com.csqtt.client.ui.legacy.ServerInventory
import com.csqtt.client.ui.legacy.ServerPlace
import com.csqtt.client.ui.legacy.deployInstance
import com.csqtt.client.ui.legacy.describeServer
import com.csqtt.client.ui.legacy.peerPortOrDefault
import com.csqtt.client.ui.legacy.performServerCheck
import com.csqtt.client.ui.legacy.planDeploy
import com.csqtt.client.ui.legacy.removableServers
import com.csqtt.client.ui.legacy.removalNotes
import com.csqtt.client.ui.legacy.bundledServerVersion
import com.csqtt.client.ui.legacy.comparableServerVersion
import com.csqtt.client.ui.legacy.friendlyDeployError
import com.csqtt.client.ui.legacy.hostHasPort
import com.csqtt.client.ui.legacy.performDeploy
import com.csqtt.client.ui.legacy.performUninstall
import com.csqtt.client.ui.legacy.splitPastedHostPort
import com.csqtt.client.ui.legacy.utils.TUNNEL_PASSWORD_RULE
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import androidx.compose.material.icons.filled.ContentCopy
import com.csqtt.client.ui.v2.components.V2TopBar
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Spacing
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.design.V2VersionFamily
import com.csqtt.client.ui.v2.dialogs.V2DeploySecretsDialog
import com.csqtt.client.ui.v2.dialogs.V2DockerInstallInfoDialog
import com.csqtt.client.ui.v2.dialogs.V2HostKeyChangedDialog
import com.csqtt.client.ui.v2.dialogs.V2SshKeysDialog
import com.csqtt.client.ui.v2.dialogs.V2UninstallConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.csqtt.client.ui.v2.components.HappField
import com.csqtt.client.ui.v2.components.HappChoice
import com.csqtt.client.ui.v2.components.HappActionRow
import com.csqtt.client.ui.legacy.utils.generate16CharPassword
import com.csqtt.client.ui.v2.components.V2SwitchSetting
import com.csqtt.client.ui.legacy.dialogs.SshKeysDialog
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val deployDraftWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private fun isValidPort(value: String): Boolean = value.toIntOrNull() in 1..65535


private fun hasWebPanelCredentials(login: String, password: String): Boolean =
    login.isNotBlank() && password.isNotBlank()

/** Last confirmed server state, persisted so the cards keep it across app restarts. */
private fun ServerInspectionResult?.confirmsDeployment(): Boolean {
    if (this == null || !isSuccess) return false
    if (serviceStatus in listOf("systemd_active", "docker_active", "process_running")) return true
    if (panelAvailable) return true
    return installedVersion.isNotBlank() &&
        installedVersion != "не определена" &&
        installedVersion != "unknown"
}

private fun DeploySettingsSnapshot.toInspectionResult(): ServerInspectionResult? =
    if (!isLoaded || lastInspectionAtMs <= 0L) {
        null
    } else {
        ServerInspectionResult(
            checkedAtMs = lastInspectionAtMs,
            isSuccess = lastInspectionOk,
            arch = lastInspectionArch,
            serviceStatus = lastInspectionServiceStatus,
            installedVersion = lastInspectionVersion,
            message = lastInspectionMessage,
            panelAvailable = lastPanelAvailable,
        )
    }

@Composable
internal fun V2DeployScreen(
    settingsStore: SettingsStore,
    savedSettings: DeploySettingsSnapshot,
    version: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = V2Theme.colors

    if (!savedSettings.isLoaded) return
    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployServers by settingsStore.deployServers.collectAsStateWithLifecycle(initialValue = emptyList())
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    val savedIp = savedSettings.ip
    val savedLogin = savedSettings.sshLogin
    val savedPassword = savedSettings.sshPassword
    val savedWebLogin = savedSettings.webLogin
    val savedWebPassword = savedSettings.webPassword
    val savedMainPass = savedSettings.mainPassword
    val savedSshPort = savedSettings.sshPort
    val savedManualPorts = savedSettings.manualPortsEnabled
    val savedSshKeysMode = savedSettings.sshKeysMode
    val savedDockerInstall = savedSettings.dockerInstall
    val savedSshPrivateKey = savedSettings.sshPrivateKey
    val savedSshKeyPassphrase = savedSettings.sshKeyPassphrase
    val savedSshCertificate = savedSettings.sshCertificate
    val savedServerPeerPort = savedSettings.serverPeerPort
    val savedServerWebPort = savedSettings.serverWebPort
    val savedSecretPathUuid = savedSettings.secretPathUuid
    val savedServerConfigured = savedSettings.serverConfigured
    val rememberedInspection = savedSettings.toInspectionResult()

    val isInspecting by ServerMaintenanceState.isInspecting.collectAsStateWithLifecycle()
    val inspectionResult by ServerMaintenanceState.inspectionResult.collectAsStateWithLifecycle()
    val isChangingSecretPath by ServerMaintenanceState.isChangingSecretPath.collectAsStateWithLifecycle()
    val changeSecretPathStage by ServerMaintenanceState.changeSecretPathStage.collectAsStateWithLifecycle()

    val serverDeployed = savedServerConfigured ||
        rememberedInspection.confirmsDeployment() ||
        inspectionResult.confirmsDeployment()

    var showConfigSheet by remember { mutableStateOf(false) }
    var showActionsMenu by rememberSaveable { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf(false) }
    var addingServer by remember { mutableStateOf(false) }
    var serverCardExpanded by rememberSaveable { mutableStateOf(true) }

    var serverName by remember(savedSettings.profile) { mutableStateOf(savedSettings.serverName) }
    val serverTitle = serverName.ifBlank { "Сервер CSQTT" }

    var host by remember(savedSettings.profile) { mutableStateOf(savedIp) }
    var sshLogin by remember(savedSettings.profile) { mutableStateOf(savedLogin) }
    var sshPassword by remember(savedSettings.profile) { mutableStateOf(savedPassword) }
    var sshPort by remember(savedSettings.profile) {
        mutableStateOf(
            if (savedIp.isBlank()) {
                ""
            } else {
                savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
            },
        )
    }
    var peerPort by remember(savedSettings.profile) { mutableStateOf(savedServerPeerPort.toString()) }
    var webPort by remember(savedSettings.profile) { mutableStateOf(savedServerWebPort.toString()) }
    var dockerInstall by remember(savedSettings.profile) { mutableStateOf(savedDockerInstall) }
    var manualPorts by remember(savedSettings.profile) { mutableStateOf(savedManualPorts) }
    var sshKeysMode by remember(savedSettings.profile) { mutableStateOf(savedSshKeysMode) }

    var showReinstallDialog by remember(savedSettings.profile) { mutableStateOf(false) }

    var showSecretsDialog by remember(savedSettings.profile) { mutableStateOf(false) }
    var showDeployOptions by remember(savedSettings.profile) { mutableStateOf(false) }
    var deployOptionsModeKey by remember(savedSettings.profile) {
        mutableStateOf(DeployActionMode.Install.name)
    }
    var showDeleteAppServerDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showSshKeysDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showDockerInfoDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    var showRegenConfirmDialog by rememberSaveable(savedSettings.profile) { mutableStateOf(false) }
    // A server that presented another SSH key than the remembered one, and the action to run again once it is trusted.
    var keyChange by remember(savedSettings.profile) { mutableStateOf<Pair<SshHostKeyStore.KeyChange, () -> Unit>?>(null) }
    // The server check, as in the classic design: what was found and offered (with the mode the user asked for), the
    // replacement awaiting its confirmation, and the removal list
    var checkPlan by remember(savedSettings.profile) { mutableStateOf<Pair<DeployActionMode, DeployPlan>?>(null) }
    var pendingReplace by remember(savedSettings.profile) { mutableStateOf<Pair<DeployActionMode, PlanAction>?>(null) }
    var removable by remember(savedSettings.profile) { mutableStateOf<List<FoundServer>?>(null) }
    var removeNotes by remember(savedSettings.profile) { mutableStateOf<List<String>>(emptyList()) }
    var pendingRemove by remember(savedSettings.profile) { mutableStateOf<FoundServer?>(null) }

    // Load drafts from their own stored slot; tab state must not restore another VPS draft.
    var syncedHost by remember(savedSettings.profile) { mutableStateOf(savedIp) }
    var syncedSshLogin by remember(savedSettings.profile) { mutableStateOf(savedLogin) }
    var syncedSshPassword by remember(savedSettings.profile) { mutableStateOf(savedPassword) }
    var syncedSshPort by remember(savedSettings.profile) { mutableStateOf(savedSshPort) }
    var syncedPeerPort by remember(savedSettings.profile) { mutableStateOf(savedServerPeerPort) }
    var syncedWebPort by remember(savedSettings.profile) { mutableStateOf(savedServerWebPort) }
    var syncedDockerInstall by remember(savedSettings.profile) { mutableStateOf(savedDockerInstall) }
    var syncedManualPorts by remember(savedSettings.profile) { mutableStateOf(savedManualPorts) }
    var syncedSshKeysMode by remember(savedSettings.profile) { mutableStateOf(savedSshKeysMode) }

    LaunchedEffect(savedIp) {
        if (savedIp != syncedHost) {
            syncedHost = savedIp
            host = savedIp
            ServerMaintenanceState.inspectionResult.value = null
        }
    }
    LaunchedEffect(savedLogin, savedPassword) {
        if (savedLogin != syncedSshLogin) {
            syncedSshLogin = savedLogin
            sshLogin = savedLogin
        }
        if (savedPassword != syncedSshPassword) {
            syncedSshPassword = savedPassword
            sshPassword = savedPassword
        }
    }
    LaunchedEffect(savedSshPort, savedServerPeerPort, savedServerWebPort) {
        if (addingServer) return@LaunchedEffect
        if (savedSshPort != syncedSshPort) {
            syncedSshPort = savedSshPort
            sshPort = savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
        }
        if (savedServerPeerPort != syncedPeerPort) {
            syncedPeerPort = savedServerPeerPort
            peerPort = savedServerPeerPort.toString()
        }
        if (savedServerWebPort != syncedWebPort) {
            syncedWebPort = savedServerWebPort
            webPort = savedServerWebPort.toString()
        }
    }
    LaunchedEffect(savedSettings.profile, savedSettings.serverName) {
        if (savedSettings.serverName.isNotBlank() && serverName.isBlank()) {
            serverName = savedSettings.serverName
        }
    }
    LaunchedEffect(savedDockerInstall) {
        if (savedDockerInstall != syncedDockerInstall) {
            syncedDockerInstall = savedDockerInstall
            dockerInstall = savedDockerInstall
        }
    }
    LaunchedEffect(savedManualPorts) {
        if (savedManualPorts != syncedManualPorts) {
            syncedManualPorts = savedManualPorts
            manualPorts = savedManualPorts
        }
    }
    LaunchedEffect(savedSshKeysMode) {
        if (savedSshKeysMode != syncedSshKeysMode) {
            syncedSshKeysMode = savedSshKeysMode
            sshKeysMode = savedSshKeysMode
        }
    }
    // Restore the last confirmed server state so the pills keep showing it after a restart.
    LaunchedEffect(savedSettings.profile, savedSettings.isLoaded, savedSettings.lastInspectionAtMs) {
        if (savedSettings.isLoaded) {
            val remembered = savedSettings.toInspectionResult()
            ServerMaintenanceState.inspectionResult.value = remembered
            if (!savedSettings.serverConfigured && remembered.confirmsDeployment()) {
                settingsStore.saveServerConfigured(true)
            }
        }
    }
    LaunchedEffect(savedSettings.profile, savedSecretPathUuid, savedSettings.isLoaded) {
        if (savedSettings.isLoaded && savedSecretPathUuid.isBlank()) {
            settingsStore.regenerateDeploySecretPathUuid()
        }
    }

    var mainPassDraft by remember(savedSettings.profile) { mutableStateOf(savedMainPass) }
    var panelLoginDraft by remember(savedSettings.profile) { mutableStateOf(savedWebLogin) }
    var panelPasswordDraft by remember(savedSettings.profile) { mutableStateOf(savedWebPassword) }
    LaunchedEffect(savedMainPass) { mainPassDraft = savedMainPass }
    LaunchedEffect(savedSettings.profile, savedSettings.isLoaded) {
        if (savedSettings.isLoaded) settingsStore.ensureDeployProfileDefaults(savedSettings.profile)
    }
    LaunchedEffect(savedWebLogin, savedWebPassword) {
        if (panelLoginDraft.isBlank()) panelLoginDraft = savedWebLogin
        if (panelPasswordDraft.isBlank()) panelPasswordDraft = savedWebPassword
    }
    val mainPasswordConfigured = isValidTunnelPassword(mainPassDraft)
    val webPanelConfigured = hasWebPanelCredentials(panelLoginDraft, panelPasswordDraft)
    val sshKeysFilled = if (savedSshPrivateKey.isNotBlank()) 1 else 0
    val sshAuthReady = sshLogin.isNotBlank() &&
        if (sshKeysMode) sshKeysFilled == 1 else sshPassword.isNotBlank()
    val portsValid = (sshPort.isBlank() || isValidPort(sshPort)) &&
        (!manualPorts || listOf(peerPort, webPort).all(::isValidPort))
    val actionsBusy = !savedSettings.isLoaded || isDeploying || isInspecting || isChangingSecretPath

    val panelDisplayUrl = buildString {
        append("https://")
        append(host.ifBlank { "IP" })
        append(":")
        append(
            if (manualPorts) {
                webPort.toIntOrNull() ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
            } else {
                CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
            },
        )
        if (savedSecretPathUuid.isNotBlank()) {
            append(if (savedSecretPathUuid.startsWith("/")) savedSecretPathUuid else "/$savedSecretPathUuid")
        } else {
            append("/uuid")
        }
    }

    fun showDeployToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            context.showRaisedToast(message, Toast.LENGTH_LONG)
        }
    }

    /** After a failed SSH action: if the server's key changed, ask whether to trust it (true), the failure stands otherwise. */
    suspend fun offerNewHostKey(target: String, port: Int, retry: () -> Unit): Boolean {
        val change = SshHostKeyStore.takeKeyChange(target, port) ?: return false
        withContext(Dispatchers.Main) { keyChange = change to retry }
        return true
    }

    /** The address field takes no port: the SSH port has its own field, the panel and Peer ports are the server's. */
    fun hostHasPortRefused(): Boolean {
        if (!hostHasPort(host)) return false
        showDeployToast("Порт SSH укажите в поле «SSH порт», а адрес — без порта")
        showConfigSheet = true
        return true
    }

    fun resetServerDraft() {
        serverName = savedSettings.serverName
        host = savedIp
        sshLogin = savedLogin
        sshPassword = savedPassword
        sshPort = savedSshPort.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
        peerPort = savedServerPeerPort.toString()
        webPort = savedServerWebPort.toString()
        dockerInstall = savedDockerInstall
        manualPorts = savedManualPorts
        sshKeysMode = savedSshKeysMode
    }

    fun clearServerDraft() {
        serverName = ""
        host = ""
        sshLogin = ""
        sshPassword = ""
        sshPort = ""
        peerPort = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString()
        webPort = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT.toString()
        dockerInstall = false
        manualPorts = false
        sshKeysMode = false
        ServerMaintenanceState.inspectionResult.value = null
    }

    fun openAddServerSheet() {
        if (actionsBusy) return
        scope.launch {
            if (savedIp.isNotBlank() || savedSettings.profile in settingsStore.occupiedProfiles.first()) {
                if (tunnelRunning) {
                    showDeployToast("Остановите туннель перед добавлением сервера")
                    return@launch
                }
                val slot = settingsStore.findFirstFreeDeployProfile()
                if (slot == null) {
                    showDeployToast("Нет свободных профилей для нового сервера")
                    return@launch
                }
                if (portsValid && savedIp.isNotBlank()) {
                    val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
                    settingsStore.saveDeployProfileDraft(savedSettings.copy(serverName = serverName, ip = host.trim(),
                        sshLogin = sshLogin, sshPassword = sshPassword, sshPort = ports.ssh.toString(),
                        serverPeerPort = ports.peer, serverWebPort = ports.web, mainPassword = mainPassDraft,
                        sshKeysMode = sshKeysMode, dockerInstall = dockerInstall,
                        webLogin = panelLoginDraft, webPassword = panelPasswordDraft))
                }
                addingServer = true
                clearServerDraft()
                settingsStore.ensureDeployProfileDefaults(slot)
                settingsStore.saveActiveProfile(slot)
            } else {
                addingServer = true
                clearServerDraft()
            }
            showConfigSheet = true
        }
    }

    fun inspectServer() {
        if (isInspecting || isDeploying || isChangingSecretPath) return
        if (hostHasPortRefused()) return
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        val deployPrivateKey = if (sshKeysMode) savedSshPrivateKey else ""
        val deployKeyPassphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
        val deployCertificate = if (sshKeysMode) savedSshCertificate else ""
        val deployPassword = if (sshKeysMode) "" else sshPassword
        ServerMaintenanceState.isInspecting.value = true
        ServerMaintenanceState.scope.launch {
            try {
                val result = ServerMaintenanceOperations.inspectServer(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = deployPassword,
                    port = ports.ssh,
                    webPort = ports.web,
                    panelSecretPath = savedSecretPathUuid,
                    privateKey = deployPrivateKey,
                    keyPassphrase = deployKeyPassphrase,
                    certificate = deployCertificate,
                )
                ServerMaintenanceState.inspectionResult.value = result
                if (result.isSuccess) {
                    settingsStore.saveDeployInspection(
                        checkedAtMs = result.checkedAtMs,
                        arch = result.arch,
                        serviceStatus = result.serviceStatus,
                        installedVersion = result.installedVersion,
                        message = result.message,
                        panelAvailable = result.panelAvailable,
                    )
                    if (!savedSettings.serverConfigured && result.confirmsDeployment()) {
                        settingsStore.saveServerConfigured(true)
                    }
                    showDeployToast(
                        "VPS: ${result.message}" +
                            if (result.panelAvailable) ", панель доступна" else ", панель недоступна",
                    )
                } else if (!offerNewHostKey(host, ports.ssh) { inspectServer() }) {
                    showDeployToast("Ошибка проверки VPS: ${result.message}")
                }
            } finally {
                ServerMaintenanceState.isInspecting.value = false
            }
        }
    }

    fun serverDraft(): DeploySettingsSnapshot {
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        return savedSettings.copy(serverName = serverName, ip = host.trim(), mainPassword = mainPassDraft,
            sshLogin = sshLogin, sshPassword = sshPassword, sshPort = ports.ssh.toString(),
            serverPeerPort = ports.peer, serverWebPort = ports.web, sshKeysMode = sshKeysMode,
            dockerInstall = dockerInstall, manualPortsEnabled = true,
            webLogin = panelLoginDraft, webPassword = panelPasswordDraft)
    }
    suspend fun persistServerDraft() { settingsStore.saveDeployProfileDraft(serverDraft()) }
    var draftDiscarded by remember(savedSettings.profile) { mutableStateOf(false) }
    val draftTracker = remember(savedSettings.profile) { mutableStateOf<DeploySettingsSnapshot?>(null) }
    SideEffect {
        draftTracker.value = if(savedSettings.isLoaded && portsValid && !actionsBusy && !addingServer && !draftDiscarded) serverDraft() else null
    }
    DisposableEffect(savedSettings.profile) {
        onDispose {
            draftTracker.value?.let { draft ->
                // Queue the departing page before a newly opened form can write newer edits.
                deployDraftWrites.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    settingsStore.saveDeployProfileDraft(draft)
                }
            }
        }
    }
    val saveServerSettings: () -> Unit = {
        val draft = serverDraft()
        val creating = addingServer
        scope.launch {
            draftDiscarded = false
            val connectionProfile = settingsStore.saveDeployProfileDraft(draft, restoreConnection = creating)
            if (!tunnelRunning && !TunnelManager.starting.value && !TunnelManager.stopping.value &&
                !TunnelManager.autoPausedForWifi.value && connectionProfile != null)
                settingsStore.selectConnectionProfile(connectionProfile)
            showConfigSheet = false
            addingServer = false
            showDeployToast("VPS и профиль подключения сохранены")
        }
    }
    LaunchedEffect(savedSettings.profile, serverName, host, sshLogin, sshPassword, sshPort, peerPort, webPort, dockerInstall, sshKeysMode, mainPassDraft, panelLoginDraft, panelPasswordDraft) {
        if (!savedSettings.isLoaded || actionsBusy || addingServer) return@LaunchedEffect
        delay(500)
        if (portsValid) persistServerDraft()
    }

    fun launchDeploy(requested: DeployActionMode, chosen: PlanAction) {
        if (DeployManager.isDeploying.value || isInspecting || isChangingSecretPath) return
        // The mode asked for holds for the 2.5 server in the standard place. Anything else the check offered (next to
        // 2.1, replacing it, the second server csqtt25, an empty VPS) is a plain install, as in the classic design: a
        // reinstall never wipes a 2.1 server's database, an update never looks for a config the side server lacks
        val mode = if (chosen.kind == PlanKind.UPDATE && chosen.instance == DeployInstance.MAIN) requested else DeployActionMode.Install
        val deployProfile = savedSettings.profile
        val requiresCredentials = mode != DeployActionMode.Update
        when {
            host.isBlank() -> {
                showDeployToast("Укажите IP-адрес сервера")
                showConfigSheet = true
                return
            }
            !sshAuthReady -> {
                showDeployToast("Укажите данные SSH для подключения к серверу")
                showConfigSheet = true
                return
            }
            hostHasPortRefused() -> return
            !portsValid -> {
                showDeployToast("Проверьте правильность портов")
                showConfigSheet = true
                return
            }
            requiresCredentials && !mainPasswordConfigured -> {
                showDeployToast("Пароль туннеля: $TUNNEL_PASSWORD_RULE")
                showSecretsDialog = true
                return
            }
            requiresCredentials && !webPanelConfigured -> {
                showDeployToast("Укажите логин и пароль web-панели")
                showSecretsDialog = true
                return
            }
        }

        val deploymentDraft = serverDraft()
        val deployMainPassword = mainPassDraft
        val deployHost = host
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        val deployPrivateKey = if (sshKeysMode) savedSshPrivateKey else ""
        val deployKeyPassphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
        val deployCertificate = if (sshKeysMode) savedSshCertificate else ""
        val deployPassword = if (sshKeysMode) "" else sshPassword
        val actionLabel = when (mode) {
            DeployActionMode.Install -> "Установка"
            DeployActionMode.Update -> "Обновление"
            DeployActionMode.Reinstall -> "Переустановка"
        }

        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                settingsStore.saveDeployProfileDraft(deploymentDraft, restoreConnection = true)
                context.startForegroundService(
                    Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" },
                )

                var deployDockerMode = dockerInstall

                if (mode == DeployActionMode.Update) {
                    DeployManager.updateProgress(0.01f, "Проверка версии на сервере…")
                    val inspection = ServerMaintenanceOperations.inspectServer(
                        context = context,
                        host = host,
                        user = sshLogin,
                        pass = deployPassword,
                        port = ports.ssh,
                        webPort = ports.web,
                        panelSecretPath = savedSecretPathUuid,
                        privateKey = deployPrivateKey,
                        keyPassphrase = deployKeyPassphrase,
                        certificate = deployCertificate,
                    )
                    if (!inspection.isSuccess) {
                        DeployManager.stopDeploy(inspection.message)
                        if (!offerNewHostKey(host, ports.ssh) { launchDeploy(requested, chosen) }) {
                            showDeployToast("Не удалось проверить версию: ${inspection.message}")
                        }
                        return@launch
                    }
                    deployDockerMode = when (inspection.serviceStatus) {
                        "docker_active" -> true
                        "systemd_active", "process_running" -> false
                        else -> dockerInstall
                    }
                    if (deployDockerMode != dockerInstall) {
                        settingsStore.saveDockerInstall(deployDockerMode)
                    }
                    val bundledVersion = bundledServerVersion(context)
                    if (bundledVersion.isBlank()) {
                        DeployManager.stopDeploy("Не удалось определить версию серверной сборки")
                        showDeployToast("Не удалось определить версию серверной сборки в приложении")
                        return@launch
                    }
                    val installedVersion = comparableServerVersion(inspection.installedVersion)
                    if (installedVersion.isNotBlank() && !isNewerVersion(installedVersion, bundledVersion)) {
                        DeployManager.stopDeploy("Уже актуальная версия")
                        showDeployToast("На сервере уже актуальная версия v$bundledVersion")
                        return@launch
                    }
                    if (installedVersion.isBlank()) {
                        TunnelManager.addDeployWarningLog(
                            "Версия на сервере не определена — будет загружена сборка v$bundledVersion",
                        )
                    } else {
                        DeployManager.updateProgress(
                            0.03f,
                            "Сервер устарел: v$installedVersion → v$bundledVersion",
                        )
                    }
                }

                val success = performDeploy(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = deployPassword,
                    port = ports.ssh,
                    mainPass = deployMainPassword,
                    webLogin = deploymentDraft.webLogin,
                    webPass = deploymentDraft.webPassword,
                    peerPort = chosen.peerPort,
                    webPort = chosen.webPort,
                    onProgress = DeployManager::updateProgress,
                    privateKey = deployPrivateKey,
                    keyPassphrase = deployKeyPassphrase,
                    certificate = deployCertificate,
                    installInDocker = deployDockerMode,
                    secretPath = if (mode == DeployActionMode.Update) "" else savedSecretPathUuid.ifBlank { "" },
                    mode = mode,
                    instance = chosen.instance,
                )
                if (success) {
                    settingsStore.saveServerConfigured(true)
                    val deployedVersion = comparableServerVersion(bundledServerVersion(context))
                    if (deployedVersion.isNotBlank()) {
                        settingsStore.saveDeployInspection(
                            checkedAtMs = System.currentTimeMillis(),
                            arch = inspectionResult?.arch
                                ?.takeIf { it.isNotBlank() && it != "unknown" }
                                .orEmpty(),
                            serviceStatus = if (deployDockerMode) "docker_active" else "systemd_active",
                            installedVersion = deployedVersion,
                            message = if (deployDockerMode) {
                                "Docker-контейнер активен"
                            } else {
                                "Служба systemd активна"
                            },
                            panelAvailable = true,
                        )
                    }
                    // the profile that started the deploy, even if another is active by now; it connects to the server
                    // just deployed, on its ports (46100 next to 2.1)
                    if (mode != DeployActionMode.Update) {
                        settingsStore.saveConnectionPassword(deployMainPassword, forProfile = settingsStore.linkedConnectionProfile(deployProfile))
                        TunnelManager.addDeployInfoLog("Пароль подключения синхронизирован с паролем сервера")
                    }
                    settingsStore.saveDeployedServer(deployProfile, deployHost, chosen.peerPort, chosen.webPort)
                    showDeployToast("$actionLabel успешно завершена")
                } else if (!offerNewHostKey(host, ports.ssh) { launchDeploy(requested, chosen) }) {
                    showDeployToast(DeployManager.lastResult.value.ifBlank { "Ошибка: $actionLabel" })
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("$actionLabel отменена")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Deploy UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200),
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                showDeployToast(message)
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure {
                    DeployManager.writeError("Не удалось остановить foreground-режим деплоя: ${it.message}")
                }
            }
        }
    }

    // The read-only server check (deploy.sh inventory) every install, update, reinstall and removal starts with
    fun runServerCheck(onChecked: (ServerInventory) -> Unit) {
        if (DeployManager.isDeploying.value || isInspecting || isChangingSecretPath) return
        val ssh = resolveServerPorts(true, sshPort, peerPort, webPort).ssh
        val target = host
        DeployManager.startDeploy()
        DeployManager.updateProgress(0.1f, "Проверка сервера…")
        DeployManager.scope.launch {
            try {
                val inventory = performServerCheck(
                    context = context,
                    host = target,
                    user = sshLogin,
                    pass = if (sshKeysMode) "" else sshPassword,
                    port = ssh,
                    privateKey = if (sshKeysMode) savedSshPrivateKey else "",
                    keyPassphrase = if (sshKeysMode) savedSshKeyPassphrase else "",
                )
                DeployManager.stopDeploy()
                withContext(Dispatchers.Main) { onChecked(inventory) }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Проверка отменена")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError("Server check error (${e.javaClass.simpleName}): ${e.message}")
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                if (!offerNewHostKey(target, ssh) { runServerCheck(onChecked) }) showDeployToast(message)
            }
        }
    }

    /** Checks the server, then offers only what fits it (install, update, next to 2.1, replace — or nothing, and why). */
    fun checkThenDeploy(mode: DeployActionMode) {
        runServerCheck { inventory ->
            val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
            checkPlan = mode to planDeploy(
                inventory = inventory,
                manualPorts = CheckPorts(ports.ssh, ports.peer, ports.web),
                profilePeerPort = ports.peer,
                dockerInstall = dockerInstall,
                appVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
            )
        }
    }

    fun startDeploy(mode: DeployActionMode) {
        if (DeployManager.isDeploying.value || isInspecting || isChangingSecretPath) return
        when {
            host.isBlank() -> {
                showDeployToast("Укажите IP-адрес сервера")
                showConfigSheet = true
                return
            }
            !sshAuthReady -> {
                showDeployToast("Укажите данные SSH для подключения к серверу")
                showConfigSheet = true
                return
            }
            hostHasPortRefused() -> return
            mode == DeployActionMode.Update && !portsValid -> {
                showDeployToast("Проверьте правильность портов")
                showConfigSheet = true
                return
            }
        }
        if (mode != DeployActionMode.Update) {
            deployOptionsModeKey = mode.name
            showDeployOptions = true
            return
        }
        checkThenDeploy(mode)
    }

    /** «Удалить CSQTT с сервера»: the check lists the servers this app installed, the user picks one. */
    fun requestUninstall() {
        if (DeployManager.isDeploying.value || isInspecting || isChangingSecretPath) return
        if (host.isBlank() || !sshAuthReady) {
            showDeployToast("Укажите данные SSH для подключения к серверу")
            showConfigSheet = true
            return
        }
        if (hostHasPortRefused()) return
        runServerCheck {
            removable = removableServers(it)
            removeNotes = removalNotes(it)
        }
    }

    fun startUninstall(server: FoundServer) {
        if (DeployManager.isDeploying.value || isInspecting || isChangingSecretPath) return
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        val uninstallPrivateKey = if (sshKeysMode) savedSshPrivateKey else ""
        val uninstallKeyPassphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
        val uninstallCertificate = if (sshKeysMode) savedSshCertificate else ""
        val uninstallPassword = if (sshKeysMode) "" else sshPassword

        DeployManager.startDeploy()
        DeployManager.scope.launch {
            try {
                context.startForegroundService(
                    Intent(context, TunnelService::class.java).apply { action = "DEPLOY_START" },
                )
                val success = performUninstall(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = uninstallPassword,
                    port = ports.ssh,
                    peerPort = server.peerPortOrDefault(),
                    onProgress = DeployManager::updateProgress,
                    privateKey = uninstallPrivateKey,
                    keyPassphrase = uninstallKeyPassphrase,
                    certificate = uninstallCertificate,
                    instance = server.deployInstance(),
                )
                if (success) {
                    // the card shows the standard place; removing the second server (csqtt25) leaves it as it is
                    if (server.place != ServerPlace.SIDE) {
                        settingsStore.saveServerConfigured(false)
                        settingsStore.clearDeployInspection()
                        ServerMaintenanceState.inspectionResult.value = null
                    }
                    showDeployToast("CSQTT успешно удален с сервера")
                } else if (!offerNewHostKey(host, ports.ssh) { startUninstall(server) }) {
                    showDeployToast(DeployManager.lastResult.value.ifBlank { "Ошибка удаления" })
                }
            } catch (e: CancellationException) {
                DeployManager.stopDeploy("Удаление отменено")
                throw e
            } catch (e: Exception) {
                val message = friendlyDeployError(e.message)
                DeployManager.writeError(
                    "Uninstall UI error (${e.javaClass.simpleName}): ${e.message}\n" +
                        e.stackTraceToString().take(1200),
                )
                TunnelManager.addDeployErrorLog(message)
                DeployManager.stopDeploy(message)
                showDeployToast(message)
            } finally {
                runCatching {
                    context.startService(Intent(context, TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                }.onFailure {
                    DeployManager.writeError("Не удалось остановить foreground-режим удаления: ${it.message}")
                }
            }
        }
    }

    fun panelUrl(): String {
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        val cleanPath = savedSecretPathUuid.trim().trimStart('/')
        val panelPath = if (cleanPath.isNotBlank()) "/$cleanPath" else ""
        return "https://${host.trim()}:${ports.web}$panelPath"
    }

    fun copyPanelLink() {
        val effectiveHost = host.trim()
        val text = if (effectiveHost.isNotBlank()) panelUrl() else savedSecretPathUuid
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("csqtt_web_panel", text))
        showDeployToast("Ссылка скопирована")
    }

    fun openPanel() {
        if (host.isBlank()) {
            showDeployToast("Сначала укажите IP или домен сервера")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(panelUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            showDeployToast("Не удалось открыть браузер")
        }
    }

    fun changeSecretPath() {
        if (actionsBusy) return
        if (host.isBlank() || !sshAuthReady) {
            showDeployToast("Укажите данные SSH для подключения к серверу")
            showConfigSheet = true
            return
        }
        if (hostHasPortRefused()) return
        val ports = resolveServerPorts(true, sshPort, peerPort, webPort)
        val candidatePath = UUID.randomUUID().toString()
        val privateKey = if (sshKeysMode) savedSshPrivateKey else ""
        val passphrase = if (sshKeysMode) savedSshKeyPassphrase else ""
        val certificate = if (sshKeysMode) savedSshCertificate else ""
        val password = if (sshKeysMode) "" else sshPassword
        ServerMaintenanceState.changeSecretPathStage.value = "Подключение к серверу…"
        ServerMaintenanceState.isChangingSecretPath.value = true
        ServerMaintenanceState.scope.launch {
            try {
                val (success, message) = ServerMaintenanceOperations.changeWebSecretPath(
                    context = context,
                    host = host,
                    user = sshLogin,
                    pass = password,
                    port = ports.ssh,
                    webPort = ports.web,
                    newSecretPath = candidatePath,
                    privateKey = privateKey,
                    keyPassphrase = passphrase,
                    certificate = certificate,
                )
                if (success) {
                    settingsStore.saveDeploySecretPathUuid(candidatePath)
                    val now = System.currentTimeMillis()
                    val base = ServerMaintenanceState.inspectionResult.value ?: rememberedInspection
                    val updated = (base ?: ServerInspectionResult(checkedAtMs = now)).copy(
                        checkedAtMs = now,
                        isSuccess = true,
                        panelAvailable = true,
                    )
                    ServerMaintenanceState.inspectionResult.value = updated
                    settingsStore.saveDeployInspection(
                        checkedAtMs = now,
                        arch = updated.arch,
                        serviceStatus = updated.serviceStatus,
                        installedVersion = updated.installedVersion,
                        message = updated.message,
                        panelAvailable = true,
                    )
                    if (!savedSettings.serverConfigured) {
                        settingsStore.saveServerConfigured(true)
                    }
                    showDeployToast(message)
                } else if (!offerNewHostKey(host, ports.ssh) { changeSecretPath() }) {
                    showDeployToast("Не удалось изменить путь: $message")
                }
            } finally {
                ServerMaintenanceState.isChangingSecretPath.value = false
                ServerMaintenanceState.changeSecretPathStage.value = ""
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        V2TopBar(version = version, title = "Деплой", onSettings = onSettings)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            V2Card(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), borderColor = colors.outline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(onClick = { showServerMenu = true }, enabled = !actionsBusy, modifier = Modifier.weight(1f)) { Text(serverTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    V2CircleIconButton(icon = Icons.Default.Edit, contentDescription = "Редактировать VPS", onClick = {
                        if (!actionsBusy && host.isNotBlank()) { addingServer = false; showConfigSheet = true }
                    })
                    V2CircleIconButton(icon = Icons.Default.Delete, contentDescription = "Удалить VPS из приложения", onClick = { if(!actionsBusy && host.isNotBlank()) showDeleteAppServerDialog = true })
                    V2CircleIconButton(icon = Icons.Default.Add, contentDescription = "Добавить VPS", onClick = { openAddServerSheet() })
                    V2CircleIconButton(icon = Icons.Default.MoreVert, contentDescription = "Действия веб-панели", onClick = {
                        if (!actionsBusy) showActionsMenu = true
                    })
                }
            }
            V2Card(contentPadding = PaddingValues(4.dp), borderColor = colors.outline) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("IP сервера", host, { value -> val split = splitPastedHostPort(host, value); host = split?.first ?: value; if (split != null) sshPort = split.second }, Modifier.weight(1.65f), enabled = !actionsBusy)
                    HappField("Порт SSH", sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), number = true, enabled = !actionsBusy)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    HappField("Логин SSH", sshLogin, { sshLogin = it }, Modifier.weight(1f), enabled = !actionsBusy)
                    if(sshKeysMode) androidx.compose.material3.OutlinedButton(onClick = { showSshKeysDialog = true }, modifier = Modifier.weight(1f).height(54.dp), enabled = !actionsBusy) { Text(if(sshKeysFilled > 0) "Ключ задан" else "Ключ SSH", fontSize = 12.sp) }
                    else HappField("Пароль SSH", sshPassword, { sshPassword = it }, Modifier.weight(1f), password = true, enabled = !actionsBusy)
                }
                HappChoice("Способ входа", if(sshKeysMode) "SSH-ключ" else "Пароль", listOf("Пароль", "SSH-ключ"), { sshKeysMode = it == "SSH-ключ" }, enabled = !actionsBusy,
                    help = "Вход на VPS по паролю или приватному ключу SSH. Пароль ключа и сертификат OpenSSH задаются в окне ключей.")
            }
            V2Card(contentPadding = PaddingValues(4.dp), borderColor = colors.outline) {
                Row(Modifier.fillMaxWidth().height(28.dp).clickable(enabled = !actionsBusy) { dockerInstall = !dockerInstall }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Установка в Docker", Modifier.weight(1f), fontSize = 14.sp)
                    com.csqtt.client.ui.v2.components.V2Switch(dockerInstall, { dockerInstall = it }, enabled = !actionsBusy)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("Порт PEER", peerPort, { peerPort = it.filter(Char::isDigit).take(5); manualPorts = true }, Modifier.weight(1f), number = true, enabled = !actionsBusy)
                    HappField("Порт WEB", webPort, { webPort = it.filter(Char::isDigit).take(5); manualPorts = true }, Modifier.weight(1f), number = true, enabled = !actionsBusy)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    HappField("Пароль туннеля", mainPassDraft, { mainPassDraft = it }, Modifier.weight(1f), password = true, enabled = !actionsBusy)
                    androidx.compose.material3.IconButton(onClick = { mainPassDraft = generate16CharPassword() }, enabled = !actionsBusy, modifier = Modifier.height(44.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Сгенерировать пароль туннеля", tint = colors.accent)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HappField("Логин веб-панели", panelLoginDraft, { panelLoginDraft = it }, Modifier.weight(1f), enabled = !actionsBusy)
                    HappField("Пароль веб-панели", panelPasswordDraft, { panelPasswordDraft = it }, Modifier.weight(1f), password = true, enabled = !actionsBusy)
                }
            }
            if(actionsBusy) Text(if(isInspecting) "Проверка VPS по SSH…" else "Операция выполняется · подробности в логах", color = colors.textSecondary, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V2PillButton(if(serverDeployed) "Обновить" else "Установить", { scope.launch { persistServerDraft(); startDeploy(if(serverDeployed) DeployActionMode.Update else DeployActionMode.Install) } }, modifier = Modifier.weight(1f), enabled = !actionsBusy, height = 44.dp, fontSize = 12.sp)
                V2PillButton("Переустановить", { showReinstallDialog = true }, modifier = Modifier.weight(1f), enabled = !actionsBusy, style = V2PillStyle.Outline, height = 44.dp, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V2PillButton("Веб-панель", { openPanel() }, modifier = Modifier.weight(1f), enabled = !actionsBusy && host.isNotBlank(), style = V2PillStyle.Outline, height = 44.dp, fontSize = 12.sp)
                V2PillButton("Удалить с сервера", { requestUninstall() }, modifier = Modifier.weight(1f), enabled = !actionsBusy && host.isNotBlank() && sshAuthReady, style = V2PillStyle.Danger, height = 44.dp, fontSize = 12.sp)
            }
        }
    }
    if(showServerMenu) androidx.compose.material3.AlertDialog(onDismissRequest = { showServerMenu = false }, title = { Text("Серверы VPS") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            deployServers.forEach { server -> HappActionRow(server.name, server.ip, {
                showServerMenu = false
                if (!actionsBusy) scope.launch {
                    if (portsValid) persistServerDraft()
                    settingsStore.saveActiveProfile(server.profile)
                    val connectionProfile = settingsStore.linkedConnectionProfile(server.profile)
                    if (!TunnelManager.running.value && !TunnelManager.starting.value && !TunnelManager.stopping.value &&
                        !TunnelManager.autoPausedForWifi.value && settingsStore.readProfileConnectionSettings(connectionProfile).peer == server.ip)
                        settingsStore.selectConnectionProfile(connectionProfile)
                }
            }) }
        }
    }, confirmButton = { androidx.compose.material3.TextButton(onClick = { showServerMenu = false; openAddServerSheet() }) { Text("Добавить VPS") } })

    if (showActionsMenu) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showActionsMenu = false }, title = { Text("Веб-панель") },
        text = { Column {
            androidx.compose.material3.TextButton(onClick = { showActionsMenu = false; showSecretsDialog = true }) { Text("Настройки веб-панели") }
            androidx.compose.material3.TextButton(onClick = { showActionsMenu = false; copyPanelLink() }, enabled = host.isNotBlank()) { Text("Копировать ссылку панели") }
            androidx.compose.material3.TextButton(onClick = { showActionsMenu = false; showRegenConfirmDialog = true }, enabled = host.isNotBlank() && sshAuthReady) { Text("Сменить путь панели") }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { showActionsMenu = false }) { Text("Закрыть") } },
    )

    // Модалка (ModalBottomSheet) для заполнения и сохранения данных сервера
    if (showConfigSheet) {
        V2ServerConfigSheet(
            serverName = serverName,
            onServerNameChange = { serverName = it },
            host = host,
            onHostChange = { value ->
                val split = splitPastedHostPort(host, value)
                host = split?.first ?: value
                if (split != null) sshPort = split.second
            },
            sshLogin = sshLogin,
            onSshLoginChange = { sshLogin = it },
            sshPassword = sshPassword,
            onSshPasswordChange = { sshPassword = it },
            sshPort = sshPort,
            onSshPortChange = { sshPort = it },
            peerPort = peerPort,
            onPeerPortChange = { peerPort = it; manualPorts = true },
            webPort = webPort,
            onWebPortChange = { webPort = it; manualPorts = true },
            sshKeysMode = sshKeysMode,
            onSshKeysModeChange = { enabled -> sshKeysMode = enabled },
            sshKeysFilled = sshKeysFilled,
            isDeploying = isDeploying,
            onOpenSshKeys = { showSshKeysDialog = true },
            onOpenSecrets = { showSecretsDialog = true },
            onSave = {
                saveServerSettings()
            },
            onDeleteServer = if (!addingServer && host.isNotBlank()) {
                {
                    showConfigSheet = false
                    addingServer = false
                    showDeleteAppServerDialog = true
                }
            } else null,
            onDismiss = {
                showConfigSheet = false
                addingServer = false
                resetServerDraft()
            },
            connectionOnly = addingServer,
        )
    }

    if (showDeployOptions) {
        val mode = runCatching { DeployActionMode.valueOf(deployOptionsModeKey) }.getOrDefault(DeployActionMode.Install)
        androidx.compose.material3.AlertDialog(onDismissRequest = { showDeployOptions = false }, title = { Text("Установка CSQTT") },
            text = { Text("VPS: $host\nSSH: $sshPort · PEER: $peerPort · WEB: $webPort\n" + if(dockerInstall) "Установка в Docker" else "") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showDeployOptions = false; scope.launch { persistServerDraft(); checkThenDeploy(mode) } }) { Text("Установить") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showDeployOptions = false }) { Text("Отмена") } })
    }

    // Диалог подтверждения удаления сервера из приложения
    if (showDeleteAppServerDialog) {
        V2DialogContainer(
            title = "Удалить из приложения?",
            subtitle = "Удаление сохранённых данных сервера",
            icon = Icons.Filled.Delete,
            onDismiss = { showDeleteAppServerDialog = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showDeleteAppServerDialog = false },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Удалить",
                        onClick = {
                            showDeleteAppServerDialog = false
                            val slot = savedSettings.profile
                            draftDiscarded = true
                            draftTracker.value = null
                            scope.launch {
                                clearServerDraft()
                                settingsStore.clearDeployProfile(forProfile = slot)
                                if(settingsStore.activeProfile.first() == slot) settingsStore.deployServers.first().firstOrNull()?.let { settingsStore.saveActiveProfile(it.profile) }
                                showDeployToast("VPS удалён из списка")
                            }
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Будут удалены данные управления этим VPS: адрес, SSH-доступ и настройки панели. " +
                    "Профиль подключения, служба и файлы на самом сервере сохранятся.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }

    // Диалог подтверждения удаления CSQTT с удалённого сервера
    keyChange?.let { (change, retry) ->
        V2HostKeyChangedDialog(
            change = change,
            onDismiss = { keyChange = null },
            onTrust = {
                keyChange = null
                SshHostKeyStore.trust(context, change)
                TunnelManager.addDeployInfoLog("Новый SSH-ключ ${change.host}:${change.port} принят: ${change.presented}")
                retry()
            },
        )
    }

    val profilePeer = resolveServerPorts(true, sshPort, peerPort, webPort).peer
    checkPlan?.let { (mode, plan) ->
        ServerCheckDialog(
            title = "На сервере",
            servers = plan.servers.map { describeServer(it, profilePeer) },
            emptyText = "csqtt не найден — сервер чистый",
            notes = plan.notes,
            choices = plan.actions.mapIndexed { index, action ->
                // the one action a reinstall wipes (launchDeploy keeps Reinstall only for it): say so, not "settings stay"
                val wipes = mode == DeployActionMode.Reinstall && action.kind == PlanKind.UPDATE &&
                    action.instance == DeployInstance.MAIN
                CheckChoice(
                    if (wipes) "Переустановить начисто" else action.title,
                    if (wipes) {
                        "Удаляются CSQTT, база клиентов, конфигурация и сертификаты, затем чистая установка. Клиенты и пароли не сохранятся"
                    } else {
                        action.detail
                    },
                    when {
                        action.destructive || wipes -> CheckChoiceStyle.DESTRUCTIVE
                        index == 0 -> CheckChoiceStyle.RECOMMENDED
                        else -> CheckChoiceStyle.OTHER
                    },
                )
            },
            onChoose = { index ->
                val action = plan.actions[index]
                checkPlan = null
                if (action.destructive) pendingReplace = mode to action else launchDeploy(mode, action)
            },
            onDismiss = { checkPlan = null },
        )
    }

    pendingReplace?.let { (mode, action) ->
        V2DialogContainer(
            title = "Заменить сервер?",
            icon = Icons.Filled.Delete,
            onDismiss = { pendingReplace = null },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { pendingReplace = null },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Заменить",
                        onClick = {
                            pendingReplace = null
                            launchDeploy(mode, action)
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = action.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }

    removable?.let { servers ->
        ServerCheckDialog(
            title = "Удаление с сервера",
            servers = emptyList(),
            emptyText = if (servers.isEmpty() && removeNotes.isEmpty()) "Серверов, установленных этим приложением, здесь нет" else "",
            notes = removeNotes,
            choices = servers.map { server ->
                val (name, detail) = describeServer(server, profilePeer)
                CheckChoice("Удалить: $name", detail, CheckChoiceStyle.DESTRUCTIVE)
            },
            onChoose = { index ->
                pendingRemove = servers[index]
                removable = null
            },
            onDismiss = { removable = null },
        )
    }

    pendingRemove?.let { server ->
        val (name, _) = describeServer(server, profilePeer)
        V2UninstallConfirmDialog(
            onDismiss = { pendingRemove = null },
            onConfirm = {
                pendingRemove = null
                startUninstall(server)
            },
            title = "Удаление: $name",
            description = if (server.place == ServerPlace.SIDE) {
                "Будет удалён второй сервер csqtt25: его бинарник, сервис или контейнер и только его правила firewall/NAT. " +
                    "Остальное на этом VPS не затрагивается. База пользователей остаётся в /etc/csqtt25.\n\nЭто действие необратимо."
            } else {
                "Будут удалены: бинарник, systemd-сервис или контейнер, конфигурация CSQTT и только помеченные правила " +
                    "firewall/NAT для CSQTT. Второй сервер (csqtt25), если он есть, не затрагивается. База пользователей " +
                    "остаётся в /etc/csqtt.\n\nЭто действие необратимо."
            },
        )
    }

    // Диалог подтверждения переустановки: очищает базу и конфигурацию на сервере
    if (showReinstallDialog) {
        V2UninstallConfirmDialog(
            onDismiss = { showReinstallDialog = false },
            onConfirm = {
                showReinstallDialog = false
                startDeploy(DeployActionMode.Reinstall)
            },
            title = "Переустановка сервера",
            description = "На сервере будут удалены CSQTT, SQLite-база, конфигурация и сертификаты, " +
                "после чего выполнится чистая установка. Все данные сервера будут потеряны.\n\n" +
                "Это действие необратимо.",
            confirmLabel = "Переустановить",
        )
    }

    if (showSecretsDialog) {
        var webLoginDraft by remember { mutableStateOf(panelLoginDraft) }
        var webPasswordDraft by remember { mutableStateOf(panelPasswordDraft) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { showSecretsDialog = false }, title = { Text("Доступ к веб-панели") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HappField("Логин веб-панели", webLoginDraft, { webLoginDraft = it })
                HappField("Пароль веб-панели", webPasswordDraft, { webPasswordDraft = it }, password = true)
                V2SwitchSetting("Секретный путь панели", savedSettings.secretPathEnabled, { enabled -> scope.launch { settingsStore.saveDeploySecretPath(enabled) } })
            }
        }, confirmButton = { androidx.compose.material3.TextButton(onClick = { panelLoginDraft = webLoginDraft; panelPasswordDraft = webPasswordDraft; val draft = serverDraft().copy(webLogin = webLoginDraft, webPassword = webPasswordDraft); scope.launch { settingsStore.saveDeployProfileDraft(draft); showSecretsDialog = false } }) { Text("Сохранить") } })
    }

    if (showSshKeysDialog) {
        SshKeysDialog(
            initialPrivateKey = savedSshPrivateKey,
            initialPassphrase = savedSshKeyPassphrase,
            initialCertificate = savedSshCertificate,
            onSave = { privateKey, keyPassphrase, certificate ->
                showSshKeysDialog = false
                val slot = savedSettings.profile
                scope.launch { settingsStore.saveSshKeys(privateKey, keyPassphrase, certificate, forProfile = slot) }
            },
            onDismiss = { showSshKeysDialog = false },
        )
    }

    if (showDockerInfoDialog) {
        V2DockerInstallInfoDialog(onDismiss = { showDockerInfoDialog = false })
    }

    if (showRegenConfirmDialog) {
        V2DialogContainer(
            title = "Смена пути панели",
            subtitle = "Перезапуск службы на сервере",
            icon = Icons.Filled.Refresh,
            onDismiss = { showRegenConfirmDialog = false },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                ) {
                    V2PillButton(
                        text = "Отмена",
                        onClick = { showRegenConfirmDialog = false },
                        style = V2PillStyle.Contrast,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                    V2PillButton(
                        text = "Сменить",
                        onClick = {
                            showRegenConfirmDialog = false
                            changeSecretPath()
                        },
                        style = V2PillStyle.Danger,
                        modifier = Modifier.weight(1f),
                        height = 50.dp,
                    )
                }
            },
        ) {
            Text(
                text = "Новый путь будет применён на сервере, служба CSQTT перезапустится. " +
                    "Старая ссылка на панель перестанет работать. Продолжить?",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                lineHeight = 20.sp,
            )
        }
    }
}

/** Компактная плашка сохранённого сервера, который сейчас не выбран. */
@Composable
private fun V2CompactServerCard(
    server: DeployServerInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val cleanVersion = comparableServerVersion(server.installedVersion).ifBlank {
        server.installedVersion
            .replace(Regex("(?i)^csqtt\\s*"), "")
            .replace(Regex("^[vV]\\s*([vV]\\s*)?"), "")
            .trim()
            .ifBlank { if (server.configured) "—" else "2.5.0" }
    }
    V2Card(
        modifier = modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = server.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2VersionFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Открыть сервер",
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                V2IpPill(ip = server.ip.ifBlank { "IP не указан" })
                V2StatusPill(configured = server.configured)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                V2ServerVersionPill(versionText = cleanVersion)
            }
        }
    }
}

/** Плашка с сервером + круглые шилдики (IP, статус, версия) + результат диагностики + кнопка корзины */
@Composable
private fun V2ServerItemCard(
    title: String,
    ip: String,
    configured: Boolean,
    version: String,
    expanded: Boolean,
    remembered: ServerInspectionResult?,
    onClick: () -> Unit,
    onDeleteFromApp: () -> Unit,
    onOpenPanel: () -> Unit,
    onCopyIp: () -> Unit,
    onCopyPanelLink: () -> Unit,
    result: ServerInspectionResult?,
    isInspecting: Boolean,
    modifier: Modifier = Modifier,
    actionsBusy: Boolean = false,
    actionsContent: @Composable () -> Unit = {},
) {
    val colors = V2Theme.colors
    val known = remembered?.takeIf { it.isSuccess }
    val knownVersion = known?.installedVersion?.takeIf {
        it.isNotBlank() && it != "—" && it != "не определена"
    }
    val rawVersion = knownVersion ?: if (configured) "" else version
    val cleanVersion = comparableServerVersion(rawVersion).ifBlank {
        rawVersion
            .replace(Regex("(?i)^csqtt\\s*"), "")
            .replace(Regex("^[vV]\\s*([vV]\\s*)?"), "")
            .trim()
            .ifBlank { if (configured) "—" else "2.5.0" }
    }
    val panelAvailable = known?.panelAvailable

    V2Card(
        modifier = modifier.fillMaxWidth(),
        shape = V2Shapes.Hero,
        color = colors.bottomSurface,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Верхняя строка: иконка, имя сервера, корзина удаления, шеврон
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2VersionFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Кнопка корзины (удаление сервера из приложения)
                Surface(
                    shape = CircleShape,
                    color = colors.field,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDeleteFromApp,
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить из приложения",
                            tint = colors.error,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Первый ряд круглых шилдиков (IP и статус развёртывания)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Шилдик IP
                V2IpPill(
                    ip = ip.ifBlank { "IP не указан" },
                    onCopy = onCopyIp,
                )

                // Шилдик статуса развёртывания
                V2StatusPill(configured = configured)
            }

            // Второй ряд круглых шилдиков (версия сервера и кнопка веб-панели с актуальным путём)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Шилдик версии сервера
                V2ServerVersionPill(versionText = cleanVersion)

                // Плашка веб-панели
                V2OpenPanelPill(
                    configured = configured,
                    panelAvailable = panelAvailable,
                    onClick = onOpenPanel,
                    onLongClick = onCopyPanelLink,
                )
            }

// Результат диагностики VPS прямо внутри плашки сервера
            AnimatedVisibility(
                visible = expanded && (isInspecting || result != null),
                enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(140)),
            ) {
                if (isInspecting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.field)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "Опрос SSH…",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = colors.accent,
                        )
                    }
                } else if (result != null) {
                    if (!result.isSuccess) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.field)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = "Не удалось подключиться: ${result.message}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = V2BodyFamily,
                                    fontSize = 13.sp,
                                ),
                                color = colors.textSecondary,
                            )
                        }
                    } else {
                        val isServiceActive = result.serviceStatus in listOf("systemd_active", "docker_active", "process_running")
                        val timeStr = remember(result.checkedAtMs) {
                            if (result.checkedAtMs > 0L) {
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.checkedAtMs))
                            } else null
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.field)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Служба CSQTT",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = V2BodyFamily,
                                        color = colors.textSecondary,
                                    ),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isServiceActive) colors.accent else colors.textMuted),
                                    )
                                    Text(
                                        text = result.message,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = V2DisplayFamily,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = if (isServiceActive) colors.accent else colors.textPrimary,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Архитектура VPS",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = V2BodyFamily,
                                        color = colors.textSecondary,
                                    ),
                                )
                                Text(
                                    text = result.arch.ifBlank { "Linux (unknown)" },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = V2VersionFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colors.textPrimary,
                                )
                            }


                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Версия на сервере",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = V2BodyFamily,
                                        color = colors.textSecondary,
                                    ),
                                )
                                Text(
                                    text = comparableServerVersion(result.installedVersion).ifBlank { result.installedVersion.ifBlank { "—" } },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = V2VersionFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colors.textPrimary,
                                )
                            }

                            if (timeStr != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Text(
                                        text = "Проверено: $timeStr",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = V2BodyFamily,
                                            fontSize = 11.sp,
                                        ),
                                        color = colors.textMuted,
                                    )
                                }
                            }

                            
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(140)),
            ) {
                actionsContent()
            }
        }
    }
}

/** Круглый шилдик IP-адреса */
@Composable
private fun V2IpPill(
    ip: String,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(V2Shapes.Pill)
            .background(V2Palette.InkDark)
            .then(
                if (onCopy != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCopy,
                    )
                } else Modifier,
            )
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(V2Palette.OnInkDark),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "IP",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                ),
                color = V2Palette.InkDark,
                modifier = Modifier.offset(x = (-0.5).dp),
            )
        }
        Text(
            text = ip,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = V2DisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
            ),
            color = V2Palette.OnInkDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Круглый шилдик статуса "Развёрнут" / "Не развёрнут" */
@Composable
private fun V2StatusPill(
    configured: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    Surface(
        shape = V2Shapes.Pill,
        color = if (configured) colors.accentSoft else colors.surfaceElevated,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (configured) colors.accent else colors.textMuted),
            )
            Text(
                text = if (configured) "Развёрнут" else "Не развёрнут",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = if (configured) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** Круглый шилдик версии сервера */
@Composable
private fun V2ServerVersionPill(
    versionText: String,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    Surface(
        shape = V2Shapes.Pill,
        color = colors.field,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "v",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    ),
                    color = colors.accent,
                )
            }
            Text(
                text = versionText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2VersionFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** Кнопка-шилдик перехода на веб-панель сервера с актуальным путём */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun V2OpenPanelPill(
    configured: Boolean,
    panelAvailable: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val isUnavailable = configured && panelAvailable == false
    val active = configured && !isUnavailable
    Surface(
        shape = V2Shapes.Pill,
        color = if (active) colors.accentSoft else colors.field,
        modifier = modifier
            .clip(V2Shapes.Pill)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (active) colors.accent else colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (active) colors.bottomSurface else colors.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = if (isUnavailable) "Панель недоступна" else "Веб-панель",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = if (active) colors.textPrimary else colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun V2DeployActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = V2Theme.colors
    val titleColor = when {
        !enabled -> colors.textMuted
        tint != null -> tint
        else -> colors.textPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.field),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) (tint ?: colors.accent) else colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = V2DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = titleColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = V2BodyFamily,
                        fontSize = 12.sp,
                    ),
                    color = colors.textMuted,
                )
            }
        }
    }
}


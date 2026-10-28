package com.csqtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal sealed interface ProfilePingResult {
    data class Success(val milliseconds: Int) : ProfilePingResult
    data class Failure(val reason: String) : ProfilePingResult
}

/** Runs the actual CSQTT binary against a socket-pair TUN, never establishing an Android VPN. */
internal object ProfileTunnelProbe {
    private val mutex = Mutex()
    private val checking = MutableStateFlow(false)
    val isChecking = checking.asStateFlow()

    suspend fun check(context: Context, profile: Int): ProfilePingResult = mutex.withLock {
        checking.value = true
        try { withContext(Dispatchers.IO) {
            if (mainTunnelBusy()) return@withContext ProfilePingResult.Failure("Отключите VPN перед проверкой")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            if (connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                return@withContext ProfilePingResult.Failure("Отключите другой VPN перед проверкой")
            }
            try {
                withTimeout(60_000) { runProbe(context.applicationContext, profile) }
            } catch (e: TimeoutCancellationException) {
                ProfilePingResult.Failure("Тайм-аут VK/TURN или сервера")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Raw process/API errors may contain credentials. Only return controlled status strings.
                ProfilePingResult.Failure("Не удалось проверить туннель")
            }
        } } finally { checking.value = false }
    }

    private fun mainTunnelBusy() = TunnelManager.running.value || TunnelManager.starting.value ||
        TunnelManager.stopping.value || TunnelManager.autoPausedForWifi.value

    private suspend fun runProbe(context: Context, profile: Int): ProfilePingResult = coroutineScope {
        val store = SettingsStore(context, readOnlyProfile = profile)
        val source = resolveConnectionSource(store, updateLogMask = false) ?: return@coroutineScope ProfilePingResult.Failure("Нет адреса сервера")
        if (source.password.isBlank()) return@coroutineScope ProfilePingResult.Failure("Нет пароля туннеля")
        val binary = File(context.applicationInfo.nativeLibraryDir, CsqttConstants.Tunnel.BINARY_NAME)
        if (!binary.exists()) return@coroutineScope ProfilePingResult.Failure("Нет ядра для этой архитектуры")
        val configuredMode = store.vkHashMode.first()
        val authMode = store.vkAuthMode.first()
        val manualFromLink = source.hashesFromLink && !source.tokenFromLink
        val jsMode = !manualFromLink && (configuredMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS || authMode == CsqttConstants.VkAuth.MODE_AUTO_JS)
        val autoApi = !manualFromLink && !jsMode && (source.tokenFromLink || configuredMode == CsqttConstants.VkAutoHash.MODE_AUTO_API)
        val token = if (source.tokenFromLink) source.vkAccessToken else store.vkAccessToken.first()
        var createdCall: VkApi.StartedCall? = null
        var process: Process? = null
        var reader: Job? = null
        var transport: ProbeTunTransport? = null
        try {
            var hashes = source.hashes
            if (autoApi) {
                if (token.isBlank()) return@coroutineScope ProfilePingResult.Failure("Войдите в VK")
                // This call belongs only to the probe, never to VkAutoCallsManager's active VPN calls.
                val result = VkApi.startCall(token)
                if (result !is VkApiResult.Ok) return@coroutineScope ProfilePingResult.Failure("Ошибка авторизации VK API")
                createdCall = result.value
                hashes = result.value.hash
            }
            if (jsMode && token.isBlank()) return@coroutineScope ProfilePingResult.Failure("Войдите в VK")
            val firstHash = hashes.split(Regex("[,\\s]+")).firstOrNull { it.isNotBlank() }.orEmpty()
            if (!jsMode && firstHash.isBlank()) return@coroutineScope ProfilePingResult.Failure("Нет VK-хешей")
            val uds = "csqtt_probe_${newTunnelSessionId()}"
            val command = mutableListOf(binary.absolutePath, "-peer", source.peer,
                "-n", CsqttConstants.Tunnel.WORKERS_PER_GROUP.toString(), "-listen", "127.0.0.1:0", "-tun-uds", uds,
                "-vk-hash-mode", if (jsMode) "auto_js" else "manual",
                "-vk-auth-mode", if (jsMode) "auto_js" else if (authMode == "auto_js") "vkcalls" else authMode,
                "-turn-transport", store.turnTransport.first(), "-srtp-mode", store.srtpMode.first(),
                "-obfs", CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
                "-captcha-mode", store.captchaMode.first(), "-fingerprint", store.selectedFingerprint.first(),
                "-client-ids", store.activeClientIds.first(), "-gen", (System.currentTimeMillis() / 1000).toString(),
                "-salt", newTunnelSessionId())
            if (firstHash.isNotBlank()) command.addAll(listOf("-vk", firstHash))
            store.customTurnIps.first().takeIf { it.isNotBlank() }?.let { command.addAll(listOf("-custom-turn-ips", it)) }
            val started = ProcessBuilder(command).directory(context.filesDir).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                environment()["CSQTT_EVENTS"] = "1"
                environment()["CSQTT_PASSWORD"] = source.password
                environment()["RAYON_NUM_THREADS"] = "2"
            }.start()
            process = started
            if (jsMode) {
                val bootstrap = Base64.encodeToString(JSONObject().put("token", token).toString().toByteArray(), Base64.NO_WRAP)
                started.outputStream.write("VK_JS_BOOTSTRAP:$bootstrap\n".toByteArray())
                started.outputStream.flush()
            }
            val config = CompletableDeferred<String>()
            reader = launch(Dispatchers.IO) {
                try {
                    started.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) when (val event = TunnelEventParser.parse(line)) {
                            is TunnelEventParser.Event.Config -> if (event.config.startsWith("TUNCONF:")) config.complete(event.config)
                            is TunnelEventParser.Event.CaptchaRequest -> config.completeExceptionally(ProbeError("Требуется капча: сначала подключитесь вручную"))
                            is TunnelEventParser.Event.Error -> if (event.fatal) config.completeExceptionally(ProbeError("Сервер или VK отклонил подключение"))
                            else -> Unit
                        }
                    }
                    if (!config.isCompleted) config.completeExceptionally(ProbeError("Ядро завершило проверку"))
                } catch (_: Exception) {
                    if (!config.isCompleted) config.completeExceptionally(ProbeError("Ядро завершило проверку"))
                }
            }
            while (!config.isCompleted) {
                ensureActive()
                if (mainTunnelBusy()) return@coroutineScope ProfilePingResult.Failure("Проверка прервана запуском VPN")
                delay(100)
            }
            // Configuration includes the allocated tunnel address and the DNS selected by the server.
            val parts = config.await().removePrefix("TUNCONF:").split(':')
            val sourceIp = InetAddress.getByName(parts[0]) as? Inet4Address
                ?: return@coroutineScope ProfilePingResult.Failure("Неподдерживаемый адрес туннеля")
            val dnsIp = parts.getOrNull(1)?.split(',')?.firstOrNull()?.let { InetAddress.getByName(it) } as? Inet4Address
                ?: return@coroutineScope ProfilePingResult.Failure("Сервер не передал IPv4 DNS")
            val tun = ProbeTunTransport()
            transport = tun
            while (true) {
                ensureActive()
                try {
                    tun.attach(uds)
                    break
                } catch (_: Exception) { delay(100) }
            }
            repeat(3) {
                val probe = TunnelProbePacket(sourceIp.address, dnsIp.address, SecureRandom().nextInt(65536))
                tun.exchange(probe, shouldAbort = ::mainTunnelBusy)?.let { return@coroutineScope ProfilePingResult.Success(it) }
            }
            ProfilePingResult.Failure("Туннель создан, ответа на тестовый пакет нет")
        } catch (e: ProbeError) {
            ProfilePingResult.Failure(e.message.orEmpty())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { p ->
                    runCatching { p.outputStream.write("FINISH_VK_CALLS\nSTOP\n".toByteArray()); p.outputStream.flush() }
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                    runCatching { p.inputStream.close() }
                }
                reader?.cancelAndJoin()
                transport?.close()
                createdCall?.let { VkApi.forceFinishCall(token, it.callId) }
            }
        }
    }

    private class ProbeError(message: String) : Exception(message)
}

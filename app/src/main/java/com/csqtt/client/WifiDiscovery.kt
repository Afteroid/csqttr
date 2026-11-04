package com.csqtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class WifiObservation(val connected: Boolean = false, val ssid: String? = null)

/** One event-driven observer, retained only by a visible Activity or the existing tunnel service. */
internal object WifiDiscovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = mutableSetOf<Any>()
    private var context: Context? = null
    private var settingsJob: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var config = WifiPreferences()
    private val connections = linkedMapOf<Network, String?>()
    private val dismissed = mutableSetOf<String>()
    private var foreground = false
    private val currentState = MutableStateFlow(WifiObservation())
    val current = currentState.asStateFlow()
    private val candidateState = MutableStateFlow<String?>(null)
    val candidate = candidateState.asStateFlow()
    val openHome = MutableStateFlow(false)
    private var posted: Pair<String, WifiNetworkListMode>? = null
    private var watchGeneration = 0

    fun acquire(context: Context, owner: Any) {
        val application = context.applicationContext
        this.context = application
        owners.add(owner)
        if (settingsJob == null) settingsJob = scope.launch {
            SettingsStore(application).wifiPreferences.collect {
                config = it
                if (it.enabled) startWatching() else stopWatching()
                refresh()
            }
        }
    }

    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            settingsJob?.cancel(); settingsJob = null
            stopWatching()
            currentState.value = WifiObservation()
            candidateState.value = null
            context?.let(::cancelNotification)
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        refresh()
    }

    fun refresh() {
        val app = context ?: return
        if (owners.isEmpty()) return
        if (config.enabled) {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            connections.keys.retainAll(networks.toSet())
            networks.forEach { network -> update(network, runCatching { cm.getNetworkCapabilities(network) }.getOrNull(), preserveCallbackName = true) }
            // WifiManager is the compatibility fallback for Android 8/9 and redacted capabilities.
            val ssid = if (connections.isEmpty()) null else currentWifiSsid(app)
                ?: connections.values.firstOrNull { it != null }
            publish(WifiObservation(connections.isNotEmpty(), ssid))
        } else {
            publish(WifiObservation())
        }
    }

    private fun startWatching() {
        val app = context ?: return
        if (callback != null) return
        val generation = ++watchGeneration
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun available(network: Network, capabilities: NetworkCapabilities?) = scope.launch {
            if (!config.enabled || callback == null || generation != watchGeneration) return@launch
            update(network, capabilities)
            val name = currentWifiSsid(app) ?: connections.values.firstOrNull { it != null }
            publish(WifiObservation(connections.isNotEmpty(), name.takeIf { connections.isNotEmpty() }))
        }
        fun lost(network: Network) = scope.launch {
            if (generation != watchGeneration || callback == null) return@launch
            connections.remove(network)
            publish(WifiObservation(connections.isNotEmpty(), connections.values.firstOrNull { it != null }))
        }
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { available(network, nc) }
            override fun onLost(network: Network) { lost(network) }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { available(network, nc) }
            override fun onLost(network: Network) { lost(network) }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        if (runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess) callback = cb
        if (receiver == null) {
            val events = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { refresh() }
            }
            ContextCompat.registerReceiver(app, events, IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = events
        }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?, preserveCallbackName: Boolean = false) {
        val app = context ?: return
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            connections.remove(network)
            return
        }
        val name = if (Build.VERSION.SDK_INT >= 29 && hasWifiSsidPermission(app))
            normalizeWifiSsid((capabilities.transportInfo as? WifiInfo)?.ssid) else null
        // Synchronous capabilities may redact an SSID received by the flagged callback.
        connections[network] = name ?: if (preserveCallbackName && hasWifiSsidAccess(app)) connections[network] else null
    }

    private fun publish(observation: WifiObservation) {
        val app = context ?: return
        val safe = if (hasWifiSsidAccess(app)) observation else observation.copy(ssid = null)
        currentState.value = safe
        candidateState.value = wifiDiscoveryCandidate(config.enabled, safe.ssid, config.saved, dismissed)
        val name = candidateState.value
        if (foreground || name == null || owners.isEmpty()) cancelNotification(app)
        else if (posted != (name to config.mode)) {
            if (WifiDiscoveryNotification.show(app, name, config.mode)) posted = name to config.mode
        }
    }

    private fun stopWatching() {
        val app = context ?: return
        watchGeneration++
        callback?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null
        connections.clear()
    }

    private fun cancelNotification(app: Context) {
        WifiDiscoveryNotification.cancel(app)
        posted = null
    }

    fun acknowledge(name: String) {
        dismissed.add(name)
        refresh()
    }
}

internal object WifiDiscoveryNotification {
    private const val CHANNEL = "wifi_discovery"
    private const val ID = 4207
    const val REMEMBER = "com.csqtt.client.WIFI_REMEMBER"
    const val DISMISS = "com.csqtt.client.WIFI_DISMISS"
    const val EXTRA_SSID = "wifi_ssid"
    const val EXTRA_MODE = "wifi_mode"
    const val OPEN = "com.csqtt.client.WIFI_OPEN"

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    fun show(context: Context, ssid: String, mode: WifiNetworkListMode): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Новые сети Wi-Fi", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Предложение сохранить подключённую сеть Wi-Fi"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        fun action(action: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, WifiDiscoveryReceiver::class.java).setAction(action)
                .setData(Uri.parse("csqtt-wifi://action/${mode.storedValue}/${Uri.encode(ssid)}"))
                .putExtra(EXTRA_SSID, ssid).putExtra(EXTRA_MODE, mode.storedValue), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).setAction(OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_c).setContentTitle(WifiCopy.NEW_NETWORK)
                .setContentText(WifiCopy.question(ssid))
                .setStyle(NotificationCompat.BigTextStyle().bigText("${WifiCopy.question(ssid)}\n${WifiCopy.explanation(mode)}"))
                .setContentIntent(open).setDeleteIntent(action(DISMISS))
                .addAction(0, WifiCopy.rememberLabel(mode), action(REMEMBER)).addAction(0, WifiCopy.NOT_NOW, action(DISMISS))
                .setSilent(true).setOnlyAlertOnce(true).setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        }.isSuccess
    }
}

/** Explicit, non-exported receiver; actions still work after process recreation. */
class WifiDiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(WifiDiscoveryNotification.REMEMBER, WifiDiscoveryNotification.DISMISS)) return
        val ssid = normalizeWifiSsid(intent.getStringExtra(WifiDiscoveryNotification.EXTRA_SSID)) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val store = SettingsStore(context.applicationContext)
                if (intent.action == WifiDiscoveryNotification.REMEMBER) {
                    val mode = WifiNetworkListMode.entries.firstOrNull {
                        it.storedValue == intent.getStringExtra(WifiDiscoveryNotification.EXTRA_MODE)
                    }
                    if (mode == null || !store.rememberWifiNetwork(ssid, mode, requireEnabled = true)) {
                        WifiDiscovery.refresh()
                        return@launch
                    }
                }
                WifiDiscovery.acknowledge(ssid)
                // Do not dismiss a newer network's notification when an old action arrives late.
                if (WifiDiscovery.candidate.value == null || WifiDiscovery.candidate.value == ssid) WifiDiscoveryNotification.cancel(context)
            } catch (error: Exception) {
                android.util.Log.w("WifiDiscovery", "Could not apply Wi-Fi notification action", error)
            } finally { pending.finish() }
        }
    }
}

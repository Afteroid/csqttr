package com.csqtt.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

internal fun hasWifiSsidPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal fun currentWifiSsid(context: Context): String? {
    if (!hasWifiSsidPermission(context)) return null
    return runCatching {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        normalizeWifiSsid(wifiManager?.connectionInfo?.ssid)
    }.getOrNull()
}

package com.csqtt.client

internal enum class WifiAutoPauseAction {
    NONE,
    PAUSE,
    RESUME,
}

internal fun wifiAutoPauseAction(
    enabled: Boolean,
    wifiConnected: Boolean,
    desiredRunning: Boolean,
    paused: Boolean,
): WifiAutoPauseAction = when {
    paused && (!enabled || !wifiConnected) -> WifiAutoPauseAction.RESUME
    enabled && wifiConnected && desiredRunning && !paused -> WifiAutoPauseAction.PAUSE
    else -> WifiAutoPauseAction.NONE
}

internal fun effectiveWifiAutoPauseConnection(
    paused: Boolean,
    callbackWifiConnected: Boolean,
    defaultPhysicalWifiConnected: Boolean?,
): Boolean = if (paused) {
    defaultPhysicalWifiConnected ?: callbackWifiConnected
} else {
    callbackWifiConnected
}

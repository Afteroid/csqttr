package com.csqtt.client

import androidx.core.app.NotificationCompat

/** Status/workers and speed remain visible without expanding the notification. */
internal fun NotificationCompat.Builder.withTunnelText(text: String): NotificationCompat.Builder {
    val lines = text.lines()
    val activeStatus = lines.lastOrNull()?.takeIf { it.startsWith(TunnelTrafficText.CONNECTED_STATUS_PREFIX) }
    return setContentTitle(activeStatus ?: "CSQTT")
        .setContentText(lines.firstOrNull().orEmpty())
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            if (activeStatus != null) lines.dropLast(1).joinToString("\n") else text
        ))
}

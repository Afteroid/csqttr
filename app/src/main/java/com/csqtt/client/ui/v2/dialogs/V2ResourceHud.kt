package com.csqtt.client.ui.v2.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.monitor.ResourceMonitor
import java.util.Locale

@Composable
fun V2ResourceHud(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = V2Theme.colors
    val snapshot by ResourceMonitor.snapshot.collectAsStateWithLifecycle()
    val session by ResourceMonitor.session.collectAsStateWithLifecycle()

    DisposableEffect(visible) {
        if (visible) ResourceMonitor.setDetailedMemoryEnabled(true)
        onDispose {
            if (visible) ResourceMonitor.setDetailedMemoryEnabled(false)
        }
    }

    val pssMb = snapshot.breakdown.totalPssMb
    val memoryText = if (pssMb > 0f) {
        String.format(Locale.US, "PSS %.0f MB", pssMb)
    } else {
        String.format(Locale.US, "RAM %.0f MB", snapshot.ramMb)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Surface(
            shape = V2Shapes.Pill,
            color = colors.bottomSurface.copy(alpha = 0.95f),
            border = BorderStroke(
                width = 1.dp,
                color = if (session.isActive) colors.accent else colors.field,
            ),
            shadowElevation = 10.dp,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // Pulsing session indicator
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (session.isActive) colors.accent else colors.textMuted),
                )

                if (session.isActive) {
                    val m = session.elapsedSeconds / 60
                    val s = session.elapsedSeconds % 60
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", m, s),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        ),
                        color = colors.accent,
                    )
                    Text(text = "•", color = colors.textMuted, fontSize = 11.sp)
                }

                Text(
                    text = String.format(Locale.US, "CPU %.1f%%", snapshot.cpuPercent),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = colors.textPrimary,
                )

                Text(text = "•", color = colors.textMuted, fontSize = 11.sp)

                Text(
                    text = memoryText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = colors.textSecondary,
                )

                if (snapshot.rxSpeedKbps > 50) {
                    Text(text = "•", color = colors.textMuted, fontSize = 11.sp)
                    val speedText = if (snapshot.rxSpeedKbps > 1024) {
                        String.format(Locale.US, "↓%.1f MB/s", snapshot.rxSpeedKbps / 1024f)
                    } else {
                        String.format(Locale.US, "↓%.0f KB/s", snapshot.rxSpeedKbps)
                    }
                    Text(
                        text = speedText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        ),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

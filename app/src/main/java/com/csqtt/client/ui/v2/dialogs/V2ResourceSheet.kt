@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csqtt.client.ui.v2.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ui.v2.components.V2Card
import com.csqtt.client.ui.v2.components.V2CircleIconButton
import com.csqtt.client.ui.v2.components.V2PillButton
import com.csqtt.client.ui.v2.components.V2PillStyle
import com.csqtt.client.ui.v2.design.V2BodyFamily
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.monitor.ResourceMonitor
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun V2ResourceSheet(
    floatingHudEnabled: Boolean,
    onToggleFloatingHud: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = V2Theme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val snapshot by ResourceMonitor.snapshot.collectAsStateWithLifecycle()
    val session by ResourceMonitor.session.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        ResourceMonitor.setDetailedMemoryEnabled(true)
        onDispose { ResourceMonitor.setDetailedMemoryEnabled(false) }
    }

    fun animateDismiss(onDone: () -> Unit = onDismiss) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDone()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.bottomSurface,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(V2Shapes.Pill)
                    .background(colors.textMuted.copy(alpha = 0.4f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (session.isActive) colors.accent else colors.textMuted),
                    )
                    Text(
                        text = "Замер ресурсов",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = V2DisplayFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                        color = colors.textPrimary,
                    )
                }

                V2CircleIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    onClick = { animateDismiss() },
                )
            }

            // Measurement Session Status Banner
            V2Card(
                shape = RoundedCornerShape(20.dp),
                color = colors.field,
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (session.isActive) "ИДЁТ ЗАМЕР..." else "ЗАМЕР НЕ АКТИВЕН",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            ),
                            color = if (session.isActive) colors.accent else colors.textMuted,
                        )
                        val elapsedMin = session.elapsedSeconds / 60
                        val elapsedSec = session.elapsedSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", elapsedMin, elapsedSec),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp,
                            ),
                            color = colors.textPrimary,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!session.isActive) {
                            V2PillButton(
                                text = "Старт",
                                onClick = { ResourceMonitor.startMeasurement() },
                                style = V2PillStyle.Contrast,
                                height = 40.dp,
                                horizontalPadding = 16.dp,
                            )
                        } else {
                            V2PillButton(
                                text = "Стоп",
                                onClick = { ResourceMonitor.stopMeasurement() },
                                style = V2PillStyle.Danger,
                                height = 40.dp,
                                horizontalPadding = 16.dp,
                            )
                        }
                        if (session.elapsedSeconds > 0 || session.samplesCount > 0) {
                            V2PillButton(
                                text = "Сброс",
                                onClick = { ResourceMonitor.resetMeasurement() },
                                style = V2PillStyle.Outline,
                                height = 40.dp,
                                horizontalPadding = 14.dp,
                            )
                        }
                    }
                }
            }

            // Live Metrics Cards Grid (CPU & RAM)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // CPU Card
                V2Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.field,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "CPU",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMuted,
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f%%", snapshot.cpuPercent),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 22.sp,
                            ),
                            color = colors.textPrimary,
                        )
                        if (session.isActive || session.samplesCount > 0) {
                            Text(
                                text = String.format(Locale.US, "Ср: %.1f%% • Пик: %.1f%%", session.avgCpuPercent, session.peakCpuPercent),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textSecondary,
                            )
                        } else {
                            Text(
                                text = "Текущая нагрузка",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                        }
                    }
                }

                // RAM Card
                V2Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.field,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val pssMb = snapshot.breakdown.totalPssMb
                        Text(
                            text = if (pssMb > 0f) "ОЗУ (PSS)" else "ОЗУ (RAM)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMuted,
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f МБ",
                                if (pssMb > 0f) pssMb else snapshot.ramMb,
                            ),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = V2DisplayFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 22.sp,
                            ),
                            color = colors.textPrimary,
                        )
                        if (session.isActive || session.samplesCount > 0) {
                            val ramDelta = session.currentRamMb - session.startRamMb
                            val deltaSign = if (ramDelta >= 0) "+" else ""
                            Text(
                                text = String.format(Locale.US, "Δ %s%.1f • Пик: %.1f", deltaSign, ramDelta, session.peakRamMb),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if (ramDelta > 10f) colors.warning else colors.textSecondary,
                            )
                        } else {
                            Text(
                                text = if (pssMb > 0f) {
                                    String.format(Locale.US, "RSS %.0f МБ", snapshot.ramMb)
                                } else {
                                    "Физическая память"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }

            // Memory breakdown (PSS)
            val breakdown = snapshot.breakdown
            if (breakdown.totalPssMb > 0f) {
                V2Card(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.field,
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "СОСТАВ ПАМЯТИ (PSS)",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = colors.textMuted,
                            )
                            Text(
                                text = String.format(Locale.US, "всего %.0f МБ", breakdown.totalPssMb),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                        }
                        MemoryRow("Java heap", breakdown.javaHeapMb)
                        MemoryRow("Native heap", breakdown.nativeHeapMb)
                        MemoryRow("Graphics", breakdown.graphicsMb)
                        MemoryRow("Code", breakdown.codeMb)
                        MemoryRow("Stack", breakdown.stackMb)
                        MemoryRow("Прочее", breakdown.privateOtherMb)
                        MemoryRow("System", breakdown.systemMb)
                    }
                }
            }

            // Network & Workers Card
            V2Card(
                shape = RoundedCornerShape(18.dp),
                color = colors.field,
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "СКОРОСТЬ И СЕТЬ",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.textMuted,
                        )
                        Text(
                            text = if (snapshot.isTunnelRunning) "Активных воркеров: ${snapshot.activeWorkers}" else "Туннель выключен",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (snapshot.isTunnelRunning) colors.accent else colors.textMuted,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Загрузка (RX)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                            Text(
                                text = if (snapshot.rxSpeedKbps > 1024) {
                                    String.format(Locale.US, "%.2f МБ/с", snapshot.rxSpeedKbps / 1024f)
                                } else {
                                    String.format(Locale.US, "%.0f КБ/с", snapshot.rxSpeedKbps)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                        }

                        Column {
                            Text(
                                text = "Отдача (TX)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                            Text(
                                text = if (snapshot.txSpeedKbps > 1024) {
                                    String.format(Locale.US, "%.2f МБ/с", snapshot.txSpeedKbps / 1024f)
                                } else {
                                    String.format(Locale.US, "%.0f КБ/с", snapshot.txSpeedKbps)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                        }

                        Column {
                            Text(
                                text = if (session.isActive) "За замер" else "Всего (сессия)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                            val totalMb = if (session.isActive) {
                                session.totalBytesTransferred / (1024f * 1024f)
                            } else {
                                snapshot.totalRxMb + snapshot.totalTxMb
                            }
                            Text(
                                text = String.format(Locale.US, "%.2f МБ", totalMb),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }

            // Floating Overlay Switch
            V2Card(
                shape = RoundedCornerShape(18.dp),
                color = colors.field,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Плавающий оверлей",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = V2BodyFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            ),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Показывать компактный замер поверх экранов",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = colors.textMuted,
                        )
                    }

                    Switch(
                        checked = floatingHudEnabled,
                        onCheckedChange = onToggleFloatingHud,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.bottomSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(label: String, valueMb: Float) {
    val colors = V2Theme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.textSecondary,
        )
        Text(
            text = String.format(Locale.US, "%.0f МБ", valueMb),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
    }
}

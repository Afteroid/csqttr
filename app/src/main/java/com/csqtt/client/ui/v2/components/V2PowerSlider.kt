package com.csqtt.client.ui.v2.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2Theme

private val DEFAULT_WORKER_STEPS = listOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f)

@Composable
fun V2PowerSlider(
    value: Float,
    maximum: Float = 100f,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onInfo: (() -> Unit)? = null,
    fixedWorkers: Int? = null,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val colors = V2Theme.colors
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val isFixed = fixedWorkers != null && fixedWorkers in 3..100
    val validSteps = remember(maximum) {
        val filtered = DEFAULT_WORKER_STEPS.filter { it <= maximum }
        if (filtered.isEmpty()) listOf(maximum.coerceAtLeast(10f)) else filtered
    }
    val minStep = validSteps.first()
    val maxStep = validSteps.last()

    fun snap(raw: Float): Float {
        return validSteps.minByOrNull { kotlin.math.abs(it - raw) } ?: raw.coerceIn(minStep, maxStep)
    }

    val normalizedValue = if (isFixed) {
        fixedWorkers.toFloat()
    } else {
        snap(value.coerceIn(minStep, maxStep))
    }

    var localValue by remember(normalizedValue) {
        mutableFloatStateOf(normalizedValue)
    }

    var isInteracting by remember { mutableStateOf(false) }

    // Loading a saved profile is immediate; animate only an ongoing gesture.
    val animatedValue by animateFloatAsState(
        targetValue = if (isFixed) fixedWorkers.toFloat() else localValue,
        animationSpec = if (isInteracting) spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = if (isInteracting) 1400f else Spring.StiffnessMediumLow,
        ) else androidx.compose.animation.core.snap(),
        label = "power_slider_val",
    )

    // Tactile thumb expansion on interaction
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isInteracting) 8.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "thumb_radius",
    )

    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val baseThumbRadiusPx = with(density) { 8.dp.toPx() }
    val trackHeightPx = with(density) { 4.dp.toPx() }
    val light = colors.background.luminance() > 0.5f
    val trackColor = if(light) Color(0xFF8EB6E8) else colors.textSecondary.copy(alpha = 0.7f)
    val activeTrackColor = if(light) colors.accent else Color(0xFF599BEE)
    val thumbColor = if(light) colors.accent else colors.textPrimary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header row: "Мощность" + "50 потоков"
        if (showHeader) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isFixed) "Потоки (лимит)" else "Потоки",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                color = colors.accent,
                modifier = if (onInfo != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onInfo,
                    )
                } else {
                    Modifier
                },
            )

            Text(
                text = "${if (isFixed) fixedWorkers else localValue.toInt()} потоков",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = V2DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                color = colors.accent,
            )
        }

        fun positionToValue(x: Float, width: Float): Float {
            val left = baseThumbRadiusPx
            val right = (width - baseThumbRadiusPx).coerceAtLeast(left + 1f)
            val fraction = ((x.coerceIn(left, right) - left) / (right - left)).coerceIn(0f, 1f)
            val raw = minStep + fraction * (maxStep - minStep)
            return snap(raw)
        }

        // Stepped slider canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(enabled, isFixed, validSteps) {
                    if (!enabled || isFixed) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isInteracting = true
                            tryAwaitRelease()
                            isInteracting = false
                        },
                        onTap = { offset ->
                            val snapped = positionToValue(offset.x, size.width.toFloat())
                            if (snapped != localValue) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                localValue = snapped
                                currentOnValueChange(snapped)
                            }
                        },
                    )
                }
                .pointerInput(enabled, isFixed, validSteps) {
                    if (!enabled || isFixed) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            isInteracting = true
                            val snapped = positionToValue(offset.x, size.width.toFloat())
                            if (snapped != localValue) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                localValue = snapped
                                currentOnValueChange(snapped)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val snapped = positionToValue(change.position.x, size.width.toFloat())
                            if (snapped != localValue) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                localValue = snapped
                                currentOnValueChange(snapped)
                            }
                        },
                        onDragEnd = {
                            isInteracting = false
                        },
                        onDragCancel = {
                            isInteracting = false
                        },
                    )
                },
        ) {
            val centerY = size.height / 2f
            val left = baseThumbRadiusPx
            val right = size.width - baseThumbRadiusPx
            val range = (maxStep - minStep).coerceAtLeast(1f)

            // 1. Draw base inactive track
            drawLine(
                color = if (enabled) trackColor else trackColor.copy(alpha = 0.5f),
                start = Offset(left, centerY),
                end = Offset(right, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            val thumbFraction = ((animatedValue - minStep) / range).coerceIn(0f, 1f)
            val thumbX = left + (right - left) * thumbFraction

            // 2. Draw filled active track (colored)
            if (thumbX > left) {
                drawLine(
                    color = if (enabled) activeTrackColor else activeTrackColor.copy(alpha = 0.5f),
                    start = Offset(left, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackHeightPx,
                    cap = StrokeCap.Round,
                )
            }

            // 4. Draw thumb handle with animated scale
            val handleWidth = with(density) { thumbRadiusDp.toPx() }
            val handleHeight = with(density) { 20.dp.toPx() }
            drawRoundRect(
                color = if (enabled) thumbColor else thumbColor.copy(alpha = 0.5f),
                topLeft = Offset(thumbX - handleWidth / 2, centerY - handleHeight / 2),
                size = Size(handleWidth, handleHeight),
                cornerRadius = CornerRadius(handleWidth / 2),
            )
        }
    }
}

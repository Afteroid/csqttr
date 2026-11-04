package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class V2FormSpacing(
    val profileHeight: Dp,
    val cardPadding: Dp,
    val gap: Dp,
    val innerGap: Dp,
)

/** Reserve readable controls first, compress whitespace before fields; never scale text. */
@Composable
internal fun V2AdaptiveForm(
    modifier: Modifier = Modifier,
    fieldRows: Int,
    controlRows: Int,
    extraHeight: Dp = 0.dp,
    content: @Composable (V2FormSpacing) -> Unit,
) {
    val base = LocalV2AdaptiveMetrics.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val labelHeight = with(density) { 16.sp.toDp() } + 3.dp
        val minField = maxOf(40.dp, with(density) { 20.sp.toDp() } + 12.dp)
        val roomyField = maxOf(48.dp, minField)
        val controlHeight = maxOf(48.dp, minField)
        val profileHeight = maxOf(48.dp, with(density) { 36.sp.toDp() } + 8.dp)
        val fixed = profileHeight + labelHeight * fieldRows + controlHeight * controlRows + extraHeight
        // Two cards (four vertical paddings), two inter-card gaps, three row gaps.
        val roomForSpacing = maxHeight - fixed - roomyField * fieldRows
        val expansion = ((roomForSpacing.value - 18f) / 42f).coerceIn(0f, 1f)
        val spacing = V2FormSpacing(profileHeight, (2 + 6 * expansion).dp,
            (2 + 6 * expansion).dp, (2 + 2 * expansion).dp)
        val spacingHeight = spacing.cardPadding * 4 + spacing.gap * 2 + spacing.innerGap * 3
        val fieldHeight = ((maxHeight - fixed - spacingHeight) / fieldRows.toFloat())
            .coerceIn(minField, roomyField)
        CompositionLocalProvider(LocalV2AdaptiveMetrics provides base.copy(
            fieldHeight = fieldHeight, rowHeight = controlHeight,
        )) { content(spacing) }
    }
}

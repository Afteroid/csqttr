package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class V2AdaptiveMetrics(
    val height: Dp = 620.dp,
    val expansion: Float = 0f,
    val dense: Boolean = false,
    val fieldHeight: Dp = (if (dense) 40 + 8 * expansion else 44 + 6 * expansion).dp,
    val rowHeight: Dp = (40 + 8 * expansion).dp,
) {
    val gap get() = (4 + 4 * expansion).dp
    val cardPadding get() = 8.dp
    val buttonHeight get() = 48.dp
    val powerSize get() = (height * 0.30f).coerceIn(100.dp, 232.dp)
}

internal val LocalV2AdaptiveMetrics = staticCompositionLocalOf { V2AdaptiveMetrics() }

/** Uses the actual space above navigation, including changes caused by the keyboard. */
@Composable
internal fun V2AdaptiveScreen(modifier: Modifier = Modifier, dense: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = V2AdaptiveMetrics(maxHeight,
            ((maxHeight.value / fontScale - (if (dense) 620f else 500f)) / 240f).coerceIn(0f, 1f), dense)
        CompositionLocalProvider(LocalV2AdaptiveMetrics provides metrics) {
            Column(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth().align(Alignment.TopCenter), content = content)
        }
    }
}

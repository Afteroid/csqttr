package com.csqtt.client.ui.v2.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.ui.v2.design.V2Colors
import com.csqtt.client.ui.v2.design.V2Theme

/** Keep the selected accent; separate light routing fields from their white sheets. */
@Composable
internal fun routingColors(): V2Colors = V2Theme.colors.let { colors ->
    if (colors.background.luminance() > 0.5f) colors.copy(
        field = Color(0xFFF0F2F5),
        surfaceElevated = Color(0xFFE5E9EF),
        outline = Color(0xFF858F9D),
    ) else colors
}

internal fun V2Colors.routingBorder(): Color? =
    outline.takeIf { background.luminance() > 0.5f }

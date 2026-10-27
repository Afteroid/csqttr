package com.csqtt.client.ui.v2.design

import androidx.compose.ui.graphics.Color

/**
 * Fixed (non-theme) palette of the v2 UI.
 *
 * Semantic theme colors live in [V2Colors] (V2DarkColors / V2LightColors) and are
 * accessed through `V2Theme.colors`. Everything here does not follow the theme:
 * the log terminal is always dark, and brand elements (version badge, GitHub and
 * IP pills) always sit on their dark ink background regardless of the palette.
 *
 * Change colors only here — screens and components must reference V2Palette.*
 * instead of hardcoding hex values.
 */
object V2Palette {

    // --- Ink (dark brand surfaces) ---

    /** Dark pill background under GitHub / IP labels and hero-card chips. */
    val InkDark: Color = Color(0xFF151519)

    /** Black gear-flower badge behind the version text. */
    val InkDarkBadge: Color = Color(0xFF000000)

    /** Text/icons on ink surfaces. */
    val OnInkDark: Color = Color.White

    /** Subtle border on ink surfaces. */
    val OnInkDarkSubtle: Color = Color(0x1AFFFFFF)

    // --- Terminal log tags (the terminal is always dark) ---

    val TagError = TagColors(
        background = Color(0xFF3E1218),
        text = Color(0xFFFF897D),
        border = Color(0xFF681D26),
    )

    val TagSuccess = TagColors(
        background = Color(0xFF052E16),
        text = Color(0xFF4ADE80),
        border = Color(0xFF14532D),
    )

    val TagInfo = TagColors(
        background = Color(0xFF0A223D),
        text = Color(0xFF3DBFFD),
        border = Color(0xFF1475FD),
    )

    val TagCount = TagColors(
        background = Color(0xFF142438),
        text = Color(0xFF3DBFFD),
        border = Color(0xFF1E4976),
    )
}

/** Background / text / border triplet of a log tag pill. */
data class TagColors(
    val background: Color,
    val text: Color,
    val border: Color,
)

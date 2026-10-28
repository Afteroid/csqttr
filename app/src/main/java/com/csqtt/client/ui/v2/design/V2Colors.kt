package com.csqtt.client.ui.v2.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class V2Colors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val field: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val flower: Color,
    val contrast: Color,
    val onContrast: Color,
    val hero: Color,
    val onHero: Color,
    val heroRow: Color,
    val navSelected: Color,
    val bottomSurface: Color,
    val terminal: Color,
    val terminalText: Color,
    val terminalMuted: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val version: Color,
)

internal val V2DarkColors = V2Colors(
    background = Color(0xFF0D0D10),
    surface = Color(0xFF1E1E24),
    surfaceElevated = Color(0xFF2B2B33),
    field = Color(0xFF1E1E24),
    outline = Color(0xFF3A3A46),
    textPrimary = Color(0xFFF3F3F7),
    textSecondary = Color(0xFF9B9BA6),
    textMuted = Color(0xFF6F6F7A),
    accent = Color(0xFF7C7BB0),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF2A2A3D),
    flower = Color(0xFF454F6E),
    contrast = Color(0xFFC9C9D2),
    onContrast = Color(0xFF17171B),
    hero = Color(0xFF6E6E80),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF7C7C8F),
    navSelected = Color(0xFF0D0D10),
    bottomSurface = Color(0xFF222228),
    terminal = Color(0xFF0A0A0C),
    terminalText = Color(0xFFD9D9E0),
    terminalMuted = Color(0xFF6F6F7A),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF9C9ACB),
)

internal val V2NordicSlateColors = V2Colors(
    background = Color(0xFF101216),
    surface = Color(0xFF191C22),
    surfaceElevated = Color(0xFF242830),
    field = Color(0xFF191C22),
    outline = Color(0xFF343942),
    textPrimary = Color(0xFFF1F3F7),
    textSecondary = Color(0xFFAEB3BD),
    textMuted = Color(0xFF8F96A3),
    accent = Color(0xFF1769E2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF15345C),
    flower = Color(0xFF245391),
    contrast = Color(0xFFB8D3EE),
    onContrast = Color(0xFF141A24),
    hero = Color(0xFF2C394B),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF37465D),
    navSelected = Color(0xFF101216),
    bottomSurface = Color(0xFF191C22),
    terminal = Color(0xFF0A0C0F),
    terminalText = Color(0xFFD6DBE4),
    terminalMuted = Color(0xFF757D8E),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF8692A6),
)

internal val V2MutedDenimColors = V2Colors(
    background = Color(0xFF090A0D),
    surface = Color(0xFF151820),
    surfaceElevated = Color(0xFF222733),
    field = Color(0xFF151820),
    outline = Color(0xFF2F384A),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFF8A93A3),
    textMuted = Color(0xFF5C6473),
    accent = Color(0xFF4F7FA8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF182638),
    flower = Color(0xFF355677),
    contrast = Color(0xFFA8CAEA),
    onContrast = Color(0xFF101620),
    hero = Color(0xFF26374A),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF31455C),
    navSelected = Color(0xFF060709),
    bottomSurface = Color(0xFF1A1E26),
    terminal = Color(0xFF07090C),
    terminalText = Color(0xFFD4DAE4),
    terminalMuted = Color(0xFF727A8A),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF828F9E),
)

internal val V2SteelBlueColors = V2Colors(
    background = Color(0xFF0B0D10),
    surface = Color(0xFF161B22),
    surfaceElevated = Color(0xFF252D3C),
    field = Color(0xFF161B22),
    outline = Color(0xFF344256),
    textPrimary = Color(0xFFF0F3F6),
    textSecondary = Color(0xFF8C96A4),
    textMuted = Color(0xFF5E6875),
    accent = Color(0xFF688EA8),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF1A2834),
    flower = Color(0xFF3F586C),
    contrast = Color(0xFFB4CBDC),
    onContrast = Color(0xFF12181E),
    hero = Color(0xFF283A48),
    onHero = Color(0xFFFFFFFF),
    heroRow = Color(0xFF334A5C),
    navSelected = Color(0xFF07090B),
    bottomSurface = Color(0xFF1C222C),
    terminal = Color(0xFF090B0E),
    terminalText = Color(0xFFD5DBE2),
    terminalMuted = Color(0xFF737D89),
    success = Color(0xFF5BC97A),
    warning = Color(0xFFF0B45C),
    error = Color(0xFFE56B6B),
    version = Color(0xFF85929F),
)

fun v2DarkColorsForPalette(palette: String?): V2Colors = when (palette) {
    "muted_denim" -> V2MutedDenimColors
    "steel_blue" -> V2SteelBlueColors
    "default", "pure_black" -> V2DarkColors
    "nordic_slate" -> V2NordicSlateColors
    else -> V2NordicSlateColors
}

internal val V2LightColors = V2Colors(
    background = Color(0xFFE5E8EE),
    surface = Color(0xFFF0F2F6),
    surfaceElevated = Color(0xFFD6DBE4),
    field = Color(0xFFE1E5ED),
    outline = Color(0xFFC0C0CB),
    textPrimary = Color(0xFF151519),
    textSecondary = Color(0xFF5F5F6B),
    textMuted = Color(0xFF8A8A96),
    accent = Color(0xFF1769E2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFDCEBFC),
    flower = Color(0xFF2864AB),
    contrast = Color(0xFF1B1B20),
    onContrast = Color(0xFFF5F5F8),
    hero = Color(0xFFD9D9E4),
    onHero = Color(0xFF17171B),
    heroRow = Color(0xFFC9C9D6),
    navSelected = Color(0xFFDCDCE5),
    bottomSurface = Color(0xFFE8E8EE),
    terminal = Color(0xFF101014),
    terminalText = Color(0xFFD9D9E0),
    terminalMuted = Color(0xFF7A7A86),
    success = Color(0xFF2E9E57),
    warning = Color(0xFFB7791F),
    error = Color(0xFFC54545),
    version = Color(0xFF5F5E93),
)

internal val LocalV2Colors = staticCompositionLocalOf { V2DarkColors }

object V2Theme {
    val colors: V2Colors
        @Composable
        @ReadOnlyComposable
        get() = LocalV2Colors.current
}

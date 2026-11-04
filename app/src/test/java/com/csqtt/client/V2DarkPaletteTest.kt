package com.csqtt.client

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.ui.v2.design.*
import org.junit.Assert.*
import org.junit.Test

class V2DarkPaletteTest {
    @Test fun archivedDesignsMigrateToOriginal250() {
        assertEquals(1, UiDesignPresets.options.size)
        assertEquals(UiDesignPresets.ORIGINAL_250, UiDesignPresets.normalize(UiDesignPresets.CLASSIC))
        assertTrue(UiDesignPresets.archivedOptions.any { it.first == UiDesignPresets.CLASSIC })
        assertFalse(UiDesignPresets.options.any { it.first == UiDesignPresets.NEW })
        assertEquals(UiDesignPresets.ORIGINAL_250, UiDesignPresets.normalize(UiDesignPresets.NEW))
        assertTrue(UiDesignPresets.archivedOptions.any { it.first == UiDesignPresets.NEW })
        assertFalse(UiDesignPresets.options.any { it.first == UiDesignPresets.SOFT_250 })
        assertEquals(UiDesignPresets.ORIGINAL_250, UiDesignPresets.normalize(UiDesignPresets.SOFT_250))
        assertEquals(V2DarkPalette.ORIGINAL, UiDesignPresets.darkPalette(UiDesignPresets.SOFT_250))
        assertEquals(v2DarkColorsForPalette(V2DarkPalette.ORIGINAL), v2DarkColorsForPalette(V2DarkPalette.SOFT))
        UiDesignPresets.options.forEach { (key, _) -> assertEquals(key, UiDesignPresets.normalize(key)) }
        assertEquals(UiDesignPresets.ORIGINAL_250, UiDesignPresets.normalize("unknown"))
        assertEquals(UiDesignPresets.ORIGINAL_250, UiDesignPresets.normalize(null))
    }

    @Test fun originalUsesClassicTokensInBothThemes() {
        val dark = v2DarkColorsForPalette(V2DarkPalette.ORIGINAL)
        assertEquals(BrandDarkColorScheme.background, dark.background)
        assertEquals(BrandDarkColorScheme.surface, dark.surface)
        assertEquals(BrandDarkColorScheme.primary, dark.accent)
        assertEquals(BrandDarkColorScheme.onSurface, dark.textPrimary)
        assertEquals(BrandDarkColorScheme.outlineVariant, dark.outline)
        assertEquals(BrandLightColorScheme.background, V2ClassicLightColors.background)
        assertEquals(BrandLightColorScheme.primary, V2ClassicLightColors.accent)
        assertEquals(BrandLightColorScheme.primaryContainer, V2ClassicLightColors.hero)
    }

    @Test fun destructiveActionsAndLightTextRemainLegible() {
        fun contrast(a: Color, b: Color): Float {
            val x = a.luminance(); val y = b.luminance()
            return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
        }
        for (colors in listOf(V2LightColors, V2ClassicLightColors, V2ClassicOriginalColors)) {
            assertTrue(contrast(colors.onDanger, colors.danger) >= 4.5f)
            assertTrue(contrast(colors.textPrimary, colors.surface) >= 4.5f)
            assertTrue(contrast(colors.error, colors.surface) >= 4.5f)
        }
        assertTrue(contrast(V2LightColors.textMuted, V2LightColors.surface) >= 4.5f)
    }
}
package com.csqtt.client.ui.v2.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun v2ColorScheme(colors: V2Colors, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.contrast,
            onSecondary = colors.onContrast,
            secondaryContainer = colors.surfaceElevated,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.version,
            onTertiary = colors.onContrast,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            outlineVariant = colors.outline,
            error = colors.error,
            onError = Color.White,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.background,
            inversePrimary = colors.accent,
            surfaceTint = Color.Transparent,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.contrast,
            onSecondary = colors.onContrast,
            secondaryContainer = colors.surfaceElevated,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.version,
            onTertiary = colors.onContrast,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            outlineVariant = colors.outline,
            error = colors.error,
            onError = Color.White,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.background,
            inversePrimary = colors.accent,
            surfaceTint = Color.Transparent,
        )
    }

@Suppress("DEPRECATION")
@Composable
fun CsqttV2Theme(
    darkTheme: Boolean = true,
    themePalette: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) v2DarkColorsForPalette(themePalette) else V2LightColors
    val colorScheme = remember(darkTheme) { v2ColorScheme(colors, darkTheme) }
    val view = LocalView.current
    val activity = view.context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current

    if (!view.isInEditMode && activity != null) {
        DisposableEffect(lifecycleOwner, activity, darkTheme) {
            val window = activity.window
            val applyBars = {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colors.background.toArgb()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            applyBars()
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) applyBars()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    CompositionLocalProvider(LocalV2Colors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = V2Typography,
            shapes = V2MaterialShapes,
            content = content,
        )
    }
}

/**
 * V2's colours and Material theme without touching the window's bars — for a V2 sheet shown inside the classic design
 * (CsqttV2Theme recolours the status and navigation bars and leaves them so).
 */
@Composable
fun CsqttV2Colors(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) v2DarkColorsForPalette("nordic_slate") else V2LightColors
    val colorScheme = remember(darkTheme) { v2ColorScheme(colors, darkTheme) }
    CompositionLocalProvider(LocalV2Colors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = V2Typography,
            shapes = V2MaterialShapes,
            content = content,
        )
    }
}

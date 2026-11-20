package com.clashflux.android.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = VergePrimary,
    onPrimary = VergeDarkTextPrimary,
    secondary = VergeAccent,
    background = VergeDarkBackground,
    surface = VergeDarkSurface,
    surfaceVariant = VergeDarkSurfaceVariant,
    onBackground = VergeDarkTextPrimary,
    onSurface = VergeDarkTextPrimary,
    outline = VergeDarkBorder,
    outlineVariant = VergeDarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = VergePrimary,
    onPrimary = VergeLightSurface,
    secondary = VergeAccent,
    background = VergeLightBackground,
    surface = VergeLightSurface,
    surfaceVariant = VergeLightSurfaceVariant,
    onBackground = VergeLightTextPrimary,
    onSurface = VergeLightTextPrimary,
    outline = VergeLightBorder,
    outlineVariant = VergeLightBorder
)

@Composable
fun ClashFluxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Default to true ClashFlux branding instead of generic dynamic color
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun clashFluxSwitchColors(): SwitchColors {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = VergePrimary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B),
        uncheckedTrackColor = if (isDark) Color(0xFF283044) else Color(0xFFE2E8F0),
        uncheckedBorderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    )
}

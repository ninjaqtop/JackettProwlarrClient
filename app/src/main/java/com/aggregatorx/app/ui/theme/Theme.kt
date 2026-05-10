package com.aggregatorx.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * AggregatorX Shielded — Mission Control Theme
 * Pure #000000 black backgrounds, #00FF41 neon green accents.
 */
private val MissionControlColorScheme = darkColorScheme(
    primary             = NeonGreen,
    onPrimary           = DarkBackground,
    primaryContainer    = NeonGreenDark,
    onPrimaryContainer  = TextPrimary,
    secondary           = NeonGreenDim,
    onSecondary         = DarkBackground,
    secondaryContainer  = Color(0xFF003D10),
    onSecondaryContainer = TextPrimary,
    tertiary            = CyberPurple,
    onTertiary          = DarkBackground,
    tertiaryContainer   = CyberPurple.copy(alpha = 0.2f),
    onTertiaryContainer = TextPrimary,
    error               = AccentRed,
    onError             = TextPrimary,
    errorContainer      = AccentRed.copy(alpha = 0.2f),
    onErrorContainer    = AccentRed,
    background          = DarkBackground,       // pure #000000
    onBackground        = TextPrimary,
    surface             = DarkSurface,          // #050505
    onSurface           = TextPrimary,
    surfaceVariant      = DarkSurfaceVariant,
    onSurfaceVariant    = TextSecondary,
    outline             = NeonGreen.copy(alpha = 0.4f),
    outlineVariant      = DarkCardHover,
    inverseSurface      = TextPrimary,
    inverseOnSurface    = DarkBackground,
    inversePrimary      = NeonGreenDark,
    surfaceTint         = NeonGreen.copy(alpha = 0.08f)
)

@Composable
fun AggregatorXTheme(
    darkTheme: Boolean = true, // always dark — mission control
    content: @Composable () -> Unit
) {
    val colorScheme = MissionControlColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = DarkBackground.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

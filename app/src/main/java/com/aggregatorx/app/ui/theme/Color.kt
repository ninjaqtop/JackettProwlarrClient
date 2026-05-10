package com.aggregatorx.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// AggregatorX Shielded — MISSION CONTROL THEME
// Pure #000000 black + #00FF41 neon green (Matrix palette)
// ============================================================

// Primary accent — pure neon green
val NeonGreen      = Color(0xFF00FF41)   // #00FF41 primary accent
val NeonGreenDim   = Color(0xFF00C832)   // dimmed variant for secondary elements
val NeonGreenDark  = Color(0xFF007A1F)   // deep green for containers

// Aliases kept for backward-compat with existing composables
val CyberCyan      = NeonGreen
val CyberCyanDark  = NeonGreenDark
val CyberBlue      = NeonGreenDim
val CyberBlueDark  = Color(0xFF003D10)
val CyberPurple    = Color(0xFF39FF14)   // electric lime highlight
val CyberPink      = NeonGreen

// Backgrounds — pure black hierarchy
val DarkBackground     = Color(0xFF000000)   // pure black
val DarkSurface        = Color(0xFF050505)   // near-black surface
val DarkSurfaceVariant = Color(0xFF0D0D0D)   // card/sheet background
val DarkCard           = Color(0xFF111111)   // elevated card
val DarkCardHover      = Color(0xFF1A1A1A)   // hover / pressed state

// Accent spectrum
val AccentGreen  = NeonGreen
val AccentOrange = Color(0xFFFF6D00)   // high-contrast orange
val AccentRed    = Color(0xFFFF1744)   // error / danger
val AccentYellow = Color(0xFFFFFF00)   // electric yellow

// Text — high-contrast on pure black
val TextPrimary   = Color(0xFFFFFFFF)   // pure white
val TextSecondary = Color(0xFFB0FFB8)   // soft neon-tinted white
val TextTertiary  = Color(0xFF4DFF6E)   // mid-brightness neon
val TextMuted     = Color(0xFF1A6630)   // muted dark green

// Gradient stops
val GradientStart = NeonGreen
val GradientMid   = NeonGreenDark
val GradientEnd   = Color(0xFF39FF14)

// Status
val StatusSuccess = NeonGreen
val StatusWarning = AccentOrange
val StatusError   = AccentRed
val StatusInfo    = NeonGreenDim

// Category colours
val CategoryStreaming = NeonGreen
val CategoryTorrent   = NeonGreenDim
val CategoryNews      = Color(0xFF39FF14)
val CategoryMedia     = Color(0xFF00E676)
val CategoryGeneral   = NeonGreen
val CategoryAPI       = AccentOrange

// AI / smart feature
val AIAccent     = NeonGreen
val SmartFeature = NeonGreenDim

// Download states
val DownloadActive   = NeonGreen
val DownloadComplete = NeonGreenDim
val DownloadPaused   = AccentOrange
val DownloadError    = AccentRed

// Score colour ramp
fun getScoreColor(score: Float): Color = when {
    score >= 80f -> NeonGreen
    score >= 60f -> NeonGreenDim
    score >= 40f -> AccentOrange
    score >= 20f -> AccentYellow
    else         -> AccentRed
}

fun getSecurityColor(score: Float): Color = when {
    score >= 80f -> NeonGreen
    score >= 60f -> NeonGreenDim
    score >= 40f -> AccentYellow
    score >= 20f -> AccentOrange
    else         -> AccentRed
}

fun getQualityColor(quality: String): Color = when {
    quality.contains("4k", ignoreCase = true) || quality.contains("2160") -> Color(0xFF39FF14)
    quality.contains("1080") || quality.contains("full hd", ignoreCase = true) -> NeonGreen
    quality.contains("720")  -> NeonGreenDim
    quality.contains("480")  -> AccentOrange
    else                     -> TextTertiary
}

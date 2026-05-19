package com.khouch.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Marquee palette — matches the web client. Inky studio blue-black
// canvas with a single warm cyc-light orange accent and a brass
// secondary. High contrast, low fatigue, one source of warmth.
object KhouchColors {
    val Bg     = Color(0xFF0D1124)
    val Bg2    = Color(0xFF161B34)
    val Bg3    = Color(0xFF1E2444)
    val Line   = Color(0xFF262C4D)
    val Fg     = Color(0xFFEBE7DF)
    val FgDim  = Color(0xFF8A92AC)
    val Accent = Color(0xFFF08245)
    val Accent2 = Color(0xFFD4A544)
}

@Composable
fun KhouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = KhouchColors.Accent,
            onPrimary = Color.White,
            background = KhouchColors.Bg,
            onBackground = KhouchColors.Fg,
            surface = KhouchColors.Bg2,
            onSurface = KhouchColors.Fg,
            surfaceVariant = KhouchColors.Bg3,
            onSurfaceVariant = KhouchColors.FgDim,
            border = KhouchColors.Line,
        ),
        // Bebas Neue (display) + Karla (body) via Google Fonts
        // downloadable API. Same families the web client uses, so the
        // visual identity matches across both clients. First launch
        // does a one-time ~60 KB font fetch via Play Services; the
        // UI renders in system fallback during that fetch.
        typography = KhouchTypography,
        content = content,
    )
}

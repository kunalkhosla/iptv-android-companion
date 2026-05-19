package com.khouch.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Marquee palette — mirrors KhouchColors in the TV app
private val BgInk        = Color(0xFF0D1124)
private val BgCard       = Color(0xFF161B34)
private val AccentOrange = Color(0xFFF08245)
private val AccentBrass  = Color(0xFFD4A544)
private val FgBone       = Color(0xFFEBE7DF)
private val FgDim        = Color(0xFF8A92AC)

private val PhoneColorScheme = darkColorScheme(
    primary        = AccentOrange,
    secondary      = AccentBrass,
    background     = BgInk,
    surface        = BgCard,
    onPrimary      = BgInk,
    onBackground   = FgBone,
    onSurface      = FgBone,
    onSurfaceVariant = FgDim,
)

@Composable
fun KhouchPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhoneColorScheme,
        content = content,
    )
}

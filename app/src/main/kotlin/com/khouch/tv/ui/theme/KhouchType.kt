package com.khouch.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.khouch.tv.R

// Google Fonts downloadable provider — the standard one shipped with
// Google Play Services. Certificates come from
// res/values/font_certs.xml. First request per font downloads ~30 KB
// to the device's font cache; subsequent reads are instant.
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// Same families the web client uses:
//   --font-display: Bebas Neue (condensed all-caps, no lowercase glyphs)
//   --font-body:    Karla (humanist sans, full character set)
// Failing to fetch falls back to FontFamily.SansSerif so the UI keeps
// rendering — first launch over a slow link briefly shows the system
// font before swapping.
val BebasNeue = FontFamily(
    Font(GoogleFont("Bebas Neue"), fontProvider, FontWeight.Normal),
)
val Karla = FontFamily(
    Font(GoogleFont("Karla"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Karla"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Karla"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Karla"), fontProvider, FontWeight.Bold),
)

// Typography palette. Display roles (channel-strip titles, hero text)
// use Bebas at slightly larger sizes for marquee impact; body roles
// (programme titles, EPG copy, buttons) use Karla. The named slots
// mirror Material 3's typography scale so existing TV-material widgets
// pick up the right family without manual overrides at each call site.
val KhouchTypography = Typography(
    // Display — used by hero, brand wordmark on large surfaces
    displayLarge  = TextStyle(fontFamily = BebasNeue, fontSize = 48.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontFamily = BebasNeue, fontSize = 36.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Normal),
    displaySmall  = TextStyle(fontFamily = BebasNeue, fontSize = 28.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Normal),
    // Headlines — rail titles, screen titles
    headlineLarge  = TextStyle(fontFamily = BebasNeue, fontSize = 28.sp, letterSpacing = 0.3.sp),
    headlineMedium = TextStyle(fontFamily = BebasNeue, fontSize = 22.sp, letterSpacing = 0.2.sp),
    headlineSmall  = TextStyle(fontFamily = BebasNeue, fontSize = 18.sp, letterSpacing = 0.2.sp),
    // Titles — mid-weight body roles (card titles, section headers)
    titleLarge  = TextStyle(fontFamily = Karla, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = Karla, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall  = TextStyle(fontFamily = Karla, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    // Body — programme titles, EPG copy, the channel-name strip on
    // the TV-guide left column (matches the web's 13px Karla 600).
    bodyLarge  = TextStyle(fontFamily = Karla, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Karla, fontSize = 14.sp),
    bodySmall  = TextStyle(fontFamily = Karla, fontSize = 12.sp),
    // Labels — meta lines, chip text, button text
    labelLarge  = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall  = TextStyle(fontFamily = Karla, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

// Named style for the channel-name strip on the TV Guide. Pulled out
// so the TvGuideScreen doesn't have to know which Material slot maps
// to it (and so future tweaks land in one place).
val KhouchChannelNameStyle: TextStyle = TextStyle(
    fontFamily = Karla,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    letterSpacing = 0.sp,
)

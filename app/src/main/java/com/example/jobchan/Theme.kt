package com.example.jobchan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val OswaldFont = GoogleFont("Oswald")
val BaiJamjureeFont = GoogleFont("Bai Jamjuree")

val OswaldFamily = FontFamily(
    Font(googleFont = OswaldFont, fontProvider = fontProvider, weight = FontWeight.Bold),
    Font(googleFont = OswaldFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = OswaldFont, fontProvider = fontProvider, weight = FontWeight.Normal)
)

val BaiJamjureeFamily = FontFamily(
    Font(googleFont = BaiJamjureeFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = BaiJamjureeFont, fontProvider = fontProvider, weight = FontWeight.Normal)
)

val JobChanTypography = Typography(
    displayLarge = TextStyle(fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = OswaldFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleSmall = TextStyle(fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = BaiJamjureeFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = BaiJamjureeFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = BaiJamjureeFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = BaiJamjureeFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = BaiJamjureeFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB3B4),
    onPrimary = Color(0xFF680016),
    primaryContainer = Color(0xFF920023),
    secondary = Color(0xFFE6BDC0),
    background = Color(0xFF1A1112),
    surface = Color(0xFF23191A),
    onSurface = Color(0xFFF0DFE0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB90033),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDADA),
    secondary = Color(0xFF775658),
    background = Color(0xFFFFF8F7),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF23191A)
)

@Composable
fun JobChanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JobChanTypography,
        content = content
    )
}

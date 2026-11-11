package com.mono.appdimmer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DimmerColorScheme = darkColorScheme(
    primary = Color(0xFFFFCF70),
    onPrimary = Color(0xFF2A1D00),
    primaryContainer = Color(0xFF3D2E0A),
    onPrimaryContainer = Color(0xFFFFE6AC),
    secondary = Color(0xFFA9C7B7),
    onSecondary = Color(0xFF123328),
    secondaryContainer = Color(0xFF243C32),
    onSecondaryContainer = Color(0xFFC5E5D4),
    background = Color(0xFF0C0D10),
    onBackground = Color(0xFFF4F5F9),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFF4F5F9),
    surfaceVariant = Color(0xFF21242B),
    onSurfaceVariant = Color(0xFFA8ACB8),
    outline = Color(0xFF3B3F49),
    outlineVariant = Color(0xFF262A31),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0906),
)

private val DimmerTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun AppDimmerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DimmerColorScheme,
        typography = DimmerTypography,
        content = content,
    )
}

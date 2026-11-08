package com.socialrecover.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Identidad visual: azul oscuro, blanco y turquesa.
val Navy = Color(0xFF0B1F3A)
val NavyLight = Color(0xFF16325C)
val Turquoise = Color(0xFF14B8A6)
val TurquoiseLight = Color(0xFF5EEAD4)
val Danger = Color(0xFFDC2626)
val Ok = Color(0xFF16A34A)

private val Light = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F5),
    onPrimaryContainer = Navy,
    secondary = Turquoise,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF064E47),
    background = Color(0xFFF6F8FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAEFF5),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = TurquoiseLight,
    onPrimary = Navy,
    primaryContainer = NavyLight,
    onPrimaryContainer = Color.White,
    secondary = Turquoise,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0F3D44),
    onSecondaryContainer = TurquoiseLight,
    background = Color(0xFF07152A),
    surface = Navy,
    surfaceVariant = NavyLight,
    error = Color(0xFFF87171),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SrpTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}

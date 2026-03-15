package com.nseassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary          = BluePrimary,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF1A2D4E),
    secondary        = GreenBull,
    onSecondary      = Color.White,
    background       = SurfaceDark,
    surface          = CardDark,
    surfaceVariant   = CardElevated,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error            = RedBear,
    outline          = DividerColor,
)

private val AppTypography = Typography(
    titleLarge  = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium  = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall   = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall  = TextStyle(fontSize = 10.sp, letterSpacing = 0.3.sp),
)

@Composable
fun NSEAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content,
    )
}

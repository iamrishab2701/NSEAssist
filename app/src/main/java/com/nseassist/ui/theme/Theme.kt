package com.nseassist.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import com.nseassist.data.local.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// ── Dark color scheme ─────────────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary            = BluePrimary,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFF1A2D4E),
    onPrimaryContainer = BlueLight,
    secondary          = GreenBull,
    onSecondary        = Color.White,
    background         = Color(0xFF080C14),   // gradient top — deep navy
    surface            = Color(0xFF0D1525),   // gradient bottom
    surfaceVariant     = Color(0xFF141E30),   // elevated card bg
    onBackground       = Color(0xFFF0F0F0),
    onSurface          = Color(0xFFF0F0F0),
    onSurfaceVariant   = Color(0xFF8E8E9A),
    error              = RedBear,
    outline            = Color(0xFF2C2C40),
)

// ── Light color scheme ────────────────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary            = Color(0xFF2B5FC9),   // darker blue for contrast on light
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF0A2868),
    secondary          = Color(0xFF00A844),   // slightly darker green for light
    onSecondary        = Color.White,
    background         = Color(0xFFEEF2FF),   // soft lavender-white gradient top
    surface            = Color(0xFFF8FAFF),   // gradient bottom
    surfaceVariant     = Color(0xFFE2E8F8),
    onBackground       = Color(0xFF0D1117),
    onSurface          = Color(0xFF0D1117),
    onSurfaceVariant   = Color(0xFF4A5568),
    error              = Color(0xFFD93025),
    outline            = Color(0xFFCBD5E1),
)

// ── Typography ────────────────────────────────────────────────────────────────
private val AppTypography = Typography(
    titleLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyMedium  = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall   = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall  = TextStyle(fontSize = 10.sp, letterSpacing = 0.3.sp),
)

// ── Theme entry point ─────────────────────────────────────────────────────────
@Composable
fun NSEAssistTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content:   @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme  = when (themeMode) {
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
        ThemeMode.SYSTEM -> systemDark
    }
    // Material You dynamic colors on API 31+ (Pixel wallpaper-adaptive)
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    val glassTokens = if (darkTheme) darkGlassTokens else lightGlassTokens

    // Make status + nav bars transparent and set correct icon tint
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window   = (view.context as Activity).window
            val wic      = WindowInsetsControllerCompat(window, view)
            window.statusBarColor     = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            wic.isAppearanceLightStatusBars     = !darkTheme
            wic.isAppearanceLightNavigationBars = !darkTheme
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    CompositionLocalProvider(LocalGlassTokens provides glassTokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = AppTypography,
            content     = content,
        )
    }
}

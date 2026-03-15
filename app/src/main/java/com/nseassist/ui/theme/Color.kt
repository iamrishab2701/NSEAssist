package com.nseassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Glass Design Tokens ───────────────────────────────────────────────────────

data class GlassTokens(
    val cardSurface:    Color,   // card/surface fill (semi-transparent)
    val cardBorder:     Color,   // card border (faint highlight)
    val gradientTop:    Color,   // background gradient start
    val gradientBottom: Color,   // background gradient end
)

val LocalGlassTokens = compositionLocalOf { darkGlassTokens }

val darkGlassTokens = GlassTokens(
    cardSurface    = Color.White.copy(alpha = 0.06f),
    cardBorder     = Color.White.copy(alpha = 0.10f),
    gradientTop    = Color(0xFF080C14),
    gradientBottom = Color(0xFF0D1525),
)

val lightGlassTokens = GlassTokens(
    cardSurface    = Color.White.copy(alpha = 0.72f),
    cardBorder     = Color.White.copy(alpha = 0.50f),
    gradientTop    = Color(0xFFEEF2FF),
    gradientBottom = Color(0xFFF8FAFF),
)

// ── Brand / Primary — static (wallpaper-adaptive via Material You on Pixel) ──
val BluePrimary = Color(0xFF5B8FF9)
val BlueLight   = Color(0xFF8AB4FA)

// ── Semantic Trading Colors — always same, never theme-dependent ──────────────
val GreenBull = Color(0xFF00C853)
val RedBear   = Color(0xFFFF4444)
val AmberWarn = Color(0xFFFFB74D)

// ── Semantic Backgrounds — composable so alpha blends correctly on any base ──
val GreenBullBg: Color @Composable get() = GreenBull.copy(alpha = 0.13f)
val RedBearBg:   Color @Composable get() = RedBear.copy(alpha = 0.12f)

// ── Structural Colors — composable getters, auto-adapt light / dark / Material You ─

/** Screen / scaffold background. */
val SurfaceDark: Color
    @Composable get() = MaterialTheme.colorScheme.background

/** Primary card fill — semi-transparent glass surface. */
val CardDark: Color
    @Composable get() = LocalGlassTokens.current.cardSurface

/** Elevated / nested card fill. */
val CardElevated: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/** Divider / outline. */
val DividerColor: Color
    @Composable get() = MaterialTheme.colorScheme.outline

/** Primary body text. */
val TextPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

/** Secondary / muted text. */
val TextSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Tertiary / very muted text. */
val TextTertiary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

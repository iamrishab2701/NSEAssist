package com.nseassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Glass Design Tokens ───────────────────────────────────────────────────────

data class GlassTokens(
    val isDark:         Boolean,
    val cardSurface:    Color,   // card/surface fill (semi-transparent glass)
    val cardBorder:     Color,   // card border (faint highlight)
    val cardSolid:      Color,   // opaque card fill (used where glass would hurt legibility)
    val sheetSurface:   Color,   // modal bottom sheet background — always opaque
    val gradientTop:    Color,   // background gradient start
    val gradientBottom: Color,   // background gradient end
)

val LocalGlassTokens = compositionLocalOf { darkGlassTokens }

val darkGlassTokens = GlassTokens(
    isDark         = true,
    cardSurface    = Color.White.copy(alpha = 0.07f),
    cardBorder     = Color.White.copy(alpha = 0.11f),
    cardSolid      = Color(0xFF141E30),   // visible opaque card for dark
    sheetSurface   = Color(0xFF111827),   // solid deep navy for sheets/overlays
    gradientTop    = Color(0xFF080C14),
    gradientBottom = Color(0xFF0D1525),
)

val lightGlassTokens = GlassTokens(
    isDark         = false,
    cardSurface    = Color.White.copy(alpha = 0.72f),
    cardBorder     = Color.White.copy(alpha = 0.50f),
    cardSolid      = Color(0xFFF0F4FF),   // visible opaque card for light
    sheetSurface   = Color(0xFFF8FAFF),   // solid white-lavender for sheets/overlays
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

/** Solid (opaque) card — use where glass would hurt legibility. */
val CardSolid: Color
    @Composable get() = LocalGlassTokens.current.cardSolid

/** Elevated / nested card fill. */
val CardElevated: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/** Opaque surface for modal bottom sheets and overlays. */
val SheetSurface: Color
    @Composable get() = LocalGlassTokens.current.sheetSurface

/** Divider / outline. */
val DividerColor: Color
    @Composable get() = MaterialTheme.colorScheme.outline

// Text colors are PINNED to legible values — not fully Material You dynamic.
// Material You can produce very muted onSurfaceVariant tones depending on
// the wallpaper, which hurts readability on a data-dense trading app.

/** Primary body text — high contrast. */
val TextPrimary: Color
    @Composable get() = if (LocalGlassTokens.current.isDark) Color(0xFFF0F0F0) else Color(0xFF0D1117)

/** Secondary / muted text — readable on both gradient and card backgrounds. */
val TextSecondary: Color
    @Composable get() = if (LocalGlassTokens.current.isDark) Color(0xFF9E9EA8) else Color(0xFF4A5568)

/** Tertiary / very muted text. */
val TextTertiary: Color
    @Composable get() = if (LocalGlassTokens.current.isDark) Color(0xFF636370) else Color(0xFF718096)

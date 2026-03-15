package com.nseassist.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Full-screen gradient background used as the outermost container on every screen.
 *
 * Wrap each [Scaffold] inside this and set the Scaffold's [containerColor] to
 * [androidx.compose.ui.graphics.Color.Transparent] so the gradient shows through.
 *
 * The gradient colors come from [LocalGlassTokens] and automatically adapt to
 * light / dark mode and Material You dynamic theming.
 */
@Composable
fun AppGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(tokens.gradientTop, tokens.gradientBottom),
                )
            ),
        content = content,
    )
}

/**
 * Semi-transparent frosted-glass card.
 *
 * Uses [LocalGlassTokens] for surface color and border so it adapts to light / dark
 * and Material You without any extra parameters.
 *
 * Replaces [androidx.compose.material3.Card] wherever a glass aesthetic is needed.
 * Set elevation to 0 — depth comes from the translucency + border, not shadow.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape:    Shape    = RoundedCornerShape(16.dp),
    content:  @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Surface(
        modifier         = modifier,
        shape            = shape,
        color            = tokens.cardSurface,
        border           = BorderStroke(0.5.dp, tokens.cardBorder),
        shadowElevation  = 0.dp,
        tonalElevation   = 0.dp,
    ) {
        Column(content = content)
    }
}

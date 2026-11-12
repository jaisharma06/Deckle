package com.avyaan.deckle.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val DeckleShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Spacing scale (spec 05 §2.3). */
public object Spacing {
    public val xs: androidx.compose.ui.unit.Dp = 4.dp
    public val s: androidx.compose.ui.unit.Dp = 8.dp
    public val m: androidx.compose.ui.unit.Dp = 12.dp
    public val l: androidx.compose.ui.unit.Dp = 16.dp
    public val xl: androidx.compose.ui.unit.Dp = 24.dp
    public val xxl: androidx.compose.ui.unit.Dp = 32.dp
    public val xxxl: androidx.compose.ui.unit.Dp = 48.dp
}

@Composable
public fun DeckleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val reduced = remember(context) { context.isReducedMotion() }
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = DeckleTypography,
            shapes = DeckleShapes,
            content = content,
        )
    }
}

/** Camera, crop and review are always dark: images read best on black and there's no white flash. */
@Composable
public fun CameraTheme(content: @Composable () -> Unit): Unit = DeckleTheme(darkTheme = true, content = content)

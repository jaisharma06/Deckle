package com.avyaan.deckle.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Deckle palette (spec 05 §2.1): deep ink teal on warm "paper" surfaces; graphite in the dark.
 * Dynamic colour is deliberately off: brand consistency and guaranteed contrast on camera screens.
 */
internal object DeckleColors {
    val InkTeal = Color(0xFF0B6E64)
    val InkTealLight = Color(0xFF5FD3C3)
    val Ember = Color(0xFFC2571A)
    val EmberLight = Color(0xFFFFB68E)
    val Paper = Color(0xFFFBF9F5)
    val Graphite = Color(0xFF121413)
}

/** Colours for the camera / crop / review screens, which are always dark. */
public object CameraColors {
    public val Scrim: Color = Color.Black.copy(alpha = 0.55f)
    public val QuadUnstable: Color = Color.White.copy(alpha = 0.70f)
    public val QuadStable: Color = DeckleColors.InkTealLight
    public val Ring: Color = DeckleColors.EmberLight
    public val Chrome: Color = Color(0xFF1D201F).copy(alpha = 0.85f)
}

internal val LightColors = lightColorScheme(
    primary = DeckleColors.InkTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = DeckleColors.Ember,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCA),
    onTertiaryContainer = Color(0xFF341000),
    background = DeckleColors.Paper,
    onBackground = Color(0xFF1B1C1A),
    surface = DeckleColors.Paper,
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF49504D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F3EE),
    surfaceContainer = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFE9E5DE),
    surfaceContainerHighest = Color(0xFFE3DFD8),
    outline = Color(0xFF79807D),
    outlineVariant = Color(0xFFC6CCC9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF30312F),
    inverseOnSurface = Color(0xFFF2F0EC),
    inversePrimary = DeckleColors.InkTealLight,
    scrim = Color.Black,
)

internal val DarkColors = darkColorScheme(
    primary = DeckleColors.InkTealLight,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFA6F2E5),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = DeckleColors.EmberLight,
    onTertiary = Color(0xFF552000),
    tertiaryContainer = Color(0xFF793000),
    onTertiaryContainer = Color(0xFFFFDBCA),
    background = DeckleColors.Graphite,
    onBackground = Color(0xFFE3E3DF),
    surface = DeckleColors.Graphite,
    onSurface = Color(0xFFE3E3DF),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF1A1C1B),
    surfaceContainer = Color(0xFF1D201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323634),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE3E3DF),
    inverseOnSurface = Color(0xFF30312F),
    inversePrimary = DeckleColors.InkTeal,
    scrim = Color.Black,
)

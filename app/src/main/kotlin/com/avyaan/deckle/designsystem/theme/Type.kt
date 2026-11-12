package com.avyaan.deckle.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.avyaan.deckle.R

/** Bundled Inter variable font (D-11, OFL). No downloadable fonts: the app is offline. */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

internal val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

internal val DeckleTypography = Typography(
    displaySmall = style(36, 44, 600, -0.02),
    headlineMedium = style(28, 36, 600, -0.015),
    headlineSmall = style(24, 32, 600, -0.01),
    titleLarge = style(22, 28, 600, -0.005),
    titleMedium = style(16, 24, 600),
    titleSmall = style(14, 20, 600),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 20, 400),
    bodySmall = style(12, 16, 400),
    labelLarge = style(14, 20, 600),
    labelMedium = style(12, 16, 600),
    labelSmall = style(11, 16, 600),
)

/** Tabular numerals for numbers that change in place (page counts, size estimates, zoom). */
public val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

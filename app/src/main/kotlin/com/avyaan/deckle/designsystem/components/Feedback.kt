package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.deckleSpring

/**
 * Rule 1 of the UX spec: every tap is acknowledged within 100 ms. A gentle squish while pressed,
 * sprung back on release, on top of the ripple.
 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.97f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, deckleSpring(DeckleMotion.Spring.Press), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Soft "paper" shadow so document covers read as physical sheets (spec 05 §2.3). */
fun Modifier.paperShadow(shape: Shape, elevation: Dp = 2.dp): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))

/** Slow shimmer for loading placeholders. Never a centred spinner (spec 05 §5). */
@Composable
fun Modifier.shimmer(): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    if (LocalReducedMotion.current) return background(base)
    val highlight = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return drawWithCache {
        val w = size.width
        val brush = Brush.linearGradient(listOf(base, highlight, base), start = Offset(w * x - w, 0f), end = Offset(w * x, size.height))
        onDrawBehind { drawRect(brush) }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(MaterialTheme.shapes.medium)
                .shimmer(),
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.6f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer(),
        )
    }
}

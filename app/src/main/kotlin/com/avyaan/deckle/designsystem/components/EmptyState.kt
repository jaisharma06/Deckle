package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing

/** Illustration + one line + one action (spec 05 §5). */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit = { ScanningPaperIllustration() },
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration()
        Spacer(Modifier.height(Spacing.xl))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.xl))
            action()
        }
    }
}

/**
 * Library empty state (spec 05 §5): a sheet of paper with a scan line sweeping down it every 2.4 s and
 * corner brackets "finding" its edges. Static when reduced motion is on. Pure Canvas — no Lottie (D-12).
 */
@Composable
fun ScanningPaperIllustration(size: Dp = 160.dp) {
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "scan")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "sweep")
    val grip by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "grip")
    val primary = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.tertiary

    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sheet = Size(w * 0.56f, w * 0.72f)
        val origin = Offset((w - sheet.width) / 2, (w - sheet.height) / 2)
        drawRoundRect(paper, origin, sheet, CornerRadius(w * 0.03f))
        drawRoundRect(ink, origin, sheet, CornerRadius(w * 0.03f), style = Stroke(w * 0.012f))
        // Text lines
        val lineX = origin.x + sheet.width * 0.14f
        repeat(5) { i ->
            val y = origin.y + sheet.height * (0.2f + i * 0.13f)
            val len = sheet.width * (if (i == 4) 0.45f else 0.72f)
            drawLine(ink, Offset(lineX, y), Offset(lineX + len, y), strokeWidth = w * 0.025f, cap = StrokeCap.Round)
        }
        // Scan line
        if (!reduced) {
            val y = origin.y + sheet.height * sweep
            drawRect(
                Brush.verticalGradient(listOf(primary.copy(alpha = 0f), primary.copy(alpha = 0.22f)), startY = y - w * 0.12f, endY = y),
                Offset(origin.x, (y - w * 0.12f).coerceAtLeast(origin.y)),
                Size(sheet.width, (y - (y - w * 0.12f).coerceAtLeast(origin.y))),
            )
            drawLine(primary, Offset(origin.x - w * 0.02f, y), Offset(origin.x + sheet.width + w * 0.02f, y), strokeWidth = w * 0.014f, cap = StrokeCap.Round)
        }
        // Corner brackets close in on the sheet
        val inset = w * (0.06f - 0.03f * (if (reduced) 1f else grip))
        brackets(origin - Offset(inset, inset), Size(sheet.width + inset * 2, sheet.height + inset * 2), accent, w * 0.1f, w * 0.022f)
    }
}

private fun DrawScope.brackets(o: Offset, s: Size, color: androidx.compose.ui.graphics.Color, len: Float, stroke: Float) {
    val corners = listOf(
        o to Offset(1f, 1f), Offset(o.x + s.width, o.y) to Offset(-1f, 1f),
        Offset(o.x + s.width, o.y + s.height) to Offset(-1f, -1f), Offset(o.x, o.y + s.height) to Offset(1f, -1f),
    )
    corners.forEach { (p, d) ->
        drawLine(color, p, Offset(p.x + d.x * len, p.y), stroke, StrokeCap.Round)
        drawLine(color, p, Offset(p.x, p.y + d.y * len), stroke, StrokeCap.Round)
    }
}

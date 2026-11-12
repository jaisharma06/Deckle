package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.theme.CameraColors
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.Spacing
import com.avyaan.deckle.designsystem.theme.deckleSpring
import com.avyaan.deckle.designsystem.theme.deckleTween
import com.avyaan.deckle.designsystem.theme.springSpec
import com.avyaan.deckle.domain.geometry.PointN
import com.avyaan.deckle.domain.geometry.Quad
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spec 05 §4.2: white disc in a ring; the disc dips on press. With auto-capture, a tertiary arc sweeps
 * from 12 o'clock while the page is held steady, and unwinds quickly (rather than vanishing) if it isn't.
 */
@Composable
fun ShutterButton(progress: Float, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val disc by animateFloatAsState(if (pressed) 0.88f else 1f, deckleSpring(DeckleMotion.Spring.Press), label = "disc")
    // Grows smoothly with the hold; when progress falls back to 0 it unwinds over Short.
    val arc by animateFloatAsState(progress, if (progress == 0f) tween(DeckleMotion.SHORT) else tween(80), label = "arc")
    val arcColor = CameraColors.Ring
    val label = stringResource(R.string.capture_shutter)

    Box(
        modifier
            .size(88.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = 4.dp.toPx()
            drawCircle(Color.White, radius = size.minDimension / 2 - ring / 2, style = Stroke(ring))
            if (arc > 0f) {
                drawArc(
                    arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * arc,
                    useCenter = false,
                    topLeft = Offset(ring / 2, ring / 2),
                    size = Size(size.width - ring, size.height - ring),
                    style = Stroke(ring, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(72.dp)
                .graphicsLayer { scaleX = disc; scaleY = disc; alpha = if (enabled) 1f else 0.6f }
                .background(Color.White, CircleShape),
        )
    }
}

/** FR-11 Single · Batch, with a sliding indicator under the active option. */
@Composable
fun ModeToggle(batch: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(false to R.string.capture_mode_single, true to R.string.capture_mode_batch)
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) ->
            val selected = value == batch
            val alpha by animateFloatAsState(if (selected) 1f else 0.6f, deckleTween(DeckleMotion.MEDIUM), label = "a")
            val underline by animateDpAsState(if (selected) 18.dp else 0.dp, deckleSpring(DeckleMotion.Spring.Pop), label = "u")
            Box(
                Modifier
                    .selectable(selected, role = Role.RadioButton) { onChange(value) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = (if (selected) CameraColors.Ring else Color.White).copy(alpha = alpha),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                        .size(underline, 3.dp)
                        .background(CameraColors.Ring, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/**
 * FR-12 batch stack: up to three offset thumbnails (−4°, 0°, 3°) with a count badge. It bumps whenever
 * the count grows so the eye catches each new page landing.
 */
@Composable
fun ThumbStack(thumbs: List<String>, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bump = remember { Animatable(1f) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(count) {
        if (count > 0 && !reduced) {
            bump.snapTo(1.12f)
            bump.animateTo(1f, springSpec(DeckleMotion.Spring.Press, reduced = false))
        }
    }
    val label = stringResource(R.string.capture_review)
    Box(
        modifier
            .size(64.dp)
            .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(enabled = count > 0, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val angles = listOf(-4f, 3f, 0f)
        thumbs.takeLast(3).forEachIndexed { i, path ->
            val angle = angles[(3 - thumbs.takeLast(3).size + i).coerceIn(0, 2)]
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp, 58.dp)
                    .rotate(angle)
                    .clip(RoundedCornerShape(4.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp)),
            )
        }
        if (count > 0) {
            Surface(
                shape = CircleShape,
                color = CameraColors.Ring,
                contentColor = Color.Black,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            ) {
                AnimatedContent(count, transitionSpec = { slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut() }, label = "n") { n ->
                    Text(n.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
        }
    }
}

/** Top-centre camera hint (spec 05 §4.3); a polite live region so TalkBack reads changes. */
@Composable
fun HintChip(text: String?, modifier: Modifier = Modifier) {
    AnimatedContent(
        text,
        transitionSpec = { (fadeIn() + slideInVertically { it / 6 }) togetherWith (fadeOut() + slideOutVertically { -it / 6 }) },
        label = "hint",
        modifier = modifier,
    ) { t ->
        if (t != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = CameraColors.Chrome,
                contentColor = Color.White,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(t, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            }
        } else {
            Box(Modifier.height(36.dp))
        }
    }
}

/** How the live quad should look (spec 05 §4.1). */
enum class QuadStyle { TRACKING, STABLE }

/**
 * The live edge overlay. Corners chase their targets with a critically damped spring so the outline feels
 * liquid rather than jumpy; with no page for 2 s it fades out instead of snapping away.
 *
 * @param toView maps a normalised point to view pixels (see `PreviewMapping`).
 */
@Composable
fun QuadOverlay(quad: Quad?, style: QuadStyle, toView: (PointN) -> Offset, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val corners = remember { List(4) { Animatable(Offset.Zero, Offset.VectorConverter) } }
    var initialised by remember { mutableStateOf(false) }
    val visibility = remember { Animatable(0f) }
    val stableColor = CameraColors.QuadStable
    val stroke by animateFloatAsState(if (style == QuadStyle.STABLE) 3f else 2f, deckleTween(DeckleMotion.SHORT), label = "w")
    val fill by animateFloatAsState(if (style == QuadStyle.STABLE) 0.14f else 0f, deckleTween(DeckleMotion.SHORT), label = "f")
    val dotPulse = remember { Animatable(0f) }

    LaunchedEffect(quad) {
        if (quad == null) {
            delay(2_000)
            visibility.animateTo(0f, tween(DeckleMotion.MEDIUM))
            initialised = false
            return@LaunchedEffect
        }
        val targets = listOf(quad.tl, quad.tr, quad.br, quad.bl).map(toView)
        if (!initialised || reduced) {
            targets.forEachIndexed { i, t -> corners[i].snapTo(t) }
            initialised = true
        } else {
            targets.forEachIndexed { i, t -> launch { corners[i].animateTo(t, springSpec(DeckleMotion.Spring.Overlay, reduced = false)) } }
        }
        visibility.animateTo(1f, tween(DeckleMotion.SHORT))
    }
    LaunchedEffect(style) {
        if (style == QuadStyle.STABLE && !reduced) {
            dotPulse.snapTo(0f)
            dotPulse.animateTo(1f, tween(DeckleMotion.LONG))
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val a = visibility.value
        if (a <= 0f) return@Canvas
        val pts = corners.map { it.value }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val color = if (style == QuadStyle.STABLE) stableColor else CameraColors.QuadUnstable
        if (fill > 0f) drawPath(path, stableColor.copy(alpha = fill * a))
        drawPath(
            path,
            color.copy(alpha = color.alpha * a),
            style = Stroke(
                width = stroke.dp.toPx(),
                join = StrokeJoin.Round,
                pathEffect = if (style == QuadStyle.TRACKING) PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx())) else null,
            ),
        )
        if (style == QuadStyle.STABLE) {
            // Corner dots pulse once: 1 → 1.3 → 1.
            val p = dotPulse.value
            val scale = 1f + 0.3f * (if (p < 0.5f) p * 2 else (1 - p) * 2)
            pts.forEach { drawCircle(stableColor.copy(alpha = a), radius = 5.dp.toPx() * scale, center = it) }
        }
    }
}

/** Expanding ring at the tap-to-focus point (FR-14). */
@Composable
fun FocusRing(point: Offset?, key: Int) {
    if (point == null) return
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { anim.animateTo(1f, tween(DeckleMotion.DELIBERATE)) }
    Canvas(Modifier.fillMaxSize()) {
        val p = anim.value
        val radius = 36.dp.toPx() * (1.3f - 0.3f * p.coerceAtMost(0.4f) / 0.4f)
        val alpha = if (p < 0.7f) 1f else (1f - (p - 0.7f) / 0.3f)
        drawCircle(Color.White.copy(alpha = alpha), radius, point, style = Stroke(2.dp.toPx()))
    }
}

/** Reports the composable's size in pixels; used to build a preview mapping. */
fun Modifier.reportSize(onSize: (IntSize) -> Unit): Modifier = onSizeChanged(onSize)

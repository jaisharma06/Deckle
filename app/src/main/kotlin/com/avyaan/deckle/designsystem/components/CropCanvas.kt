package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.springSpec
import com.avyaan.deckle.domain.geometry.Corner
import com.avyaan.deckle.domain.geometry.CropHandle
import com.avyaan.deckle.domain.geometry.Edge
import com.avyaan.deckle.domain.geometry.NudgeDirection
import com.avyaan.deckle.domain.geometry.PointN
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The crop editor surface (spec 05 §4.4): the image fitted with a margin and turned by [rotation], a mask
 * outside the quad, corner and mid-edge handles, a loupe that keeps clear of the thumb, and TalkBack
 * actions on every corner (spec 05 §8).
 *
 * Geometry is reported in normalised units of the unrotated image, matching the domain's [Quad].
 */
@Composable
fun CropCanvas(
    image: ImageBitmap?,
    quad: Quad,
    rotation: Rotation,
    activeHandle: CropHandle?,
    rejections: Int,
    onDragStart: (CropHandle) -> Unit,
    onDrag: (PointN) -> Unit,
    onDragEnd: () -> Unit,
    onNudge: (Corner, NudgeDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    // Rotation animates the short way round, accumulating turns so 270° → 0° spins forward, not back.
    var turns by remember { mutableIntStateOf(rotation.degrees / 90) }
    LaunchedEffect(rotation) {
        val current = Math.floorMod(turns, 4)
        val target = rotation.degrees / 90
        val delta = Math.floorMod(target - current, 4).let { if (it == 3) -1 else it }
        turns += delta
    }
    val angle by animateFloatAsState(turns * 90f, if (reduced) tween(0) else tween(DeckleMotion.MEDIUM, easing = DeckleMotion.Standard), label = "rot")

    // Handles glide when the quad jumps (Full page ⇄ Auto) and track the finger exactly while dragging.
    val corners = remember { List(4) { i -> Animatable(quad.corners[i].toOffset(), Offset.VectorConverter) } }
    val dragging = activeHandle != null
    LaunchedEffect(quad, dragging) {
        quad.corners.forEachIndexed { i, p ->
            launch {
                if (dragging || reduced) corners[i].snapTo(p.toOffset())
                else corners[i].animateTo(p.toOffset(), springSpec(DeckleMotion.Spring.Handle, reduced))
            }
        }
    }

    // Rejected move: the handle shakes (3 cycles, 4 dp) and the edges flash the error colour.
    val shake = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(rejections) {
        if (rejections == 0) return@LaunchedEffect
        launch { flash.snapTo(1f); flash.animateTo(0f, tween(DeckleMotion.SHORT)) }
        if (!reduced) {
            repeat(3) { shake.animateTo(1f, tween(30)); shake.animateTo(-1f, tween(60)) }
            shake.animateTo(0f, tween(30))
        }
    }
    val handleScale by animateFloatAsState(if (dragging) 1.25f else 1f, springSpec(DeckleMotion.Spring.Press, reduced), label = "hs")

    BoxWithConstraints(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val aspect = image?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
        val shownAspect = if (rotation.swapsAxes) 1f / aspect else aspect
        val (fitW, fitH) = if (cw / ch > shownAspect) ch * shownAspect to ch else cw to cw / shownAspect
        // The content box is the unrotated image; rotating it by 90° makes it fill fitW × fitH.
        val boxW = if (rotation.swapsAxes) fitH else fitW
        val boxH = if (rotation.swapsAxes) fitW else fitH
        val boxSize = IntSize(boxW.roundToInt(), boxH.roundToInt())
        val latestQuad by rememberUpdatedState(quad)
        val handleRadius = with(density) { 11.dp.toPx() }
        val hitRadius = with(density) { 32.dp.toPx() }

        Box(
            Modifier
                .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                .graphicsLayer { rotationZ = angle },
        ) {
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().shimmer())
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(boxSize) {
                        var active: CropHandle? = null
                        detectDragGestures(
                            onDragStart = { p ->
                                active = hitTest(latestQuad, p, boxSize, hitRadius)
                                active?.let(onDragStart)
                            },
                            onDrag = { change, amount ->
                                if (active != null) {
                                    change.consume()
                                    onDrag(PointN(amount.x / boxSize.width, amount.y / boxSize.height))
                                }
                            },
                            onDragEnd = { if (active != null) onDragEnd(); active = null },
                            onDragCancel = { if (active != null) onDragEnd(); active = null },
                        )
                    },
            ) {
                val pts = corners.map { Offset(it.value.x * size.width, it.value.y * size.height) }
                val quadPath = Path().apply {
                    moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
                }
                // Mask outside the quad (even-odd: everything minus the quad).
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addPath(quadPath)
                }
                drawPath(mask, Color.Black.copy(alpha = 0.5f))
                val edgeColor = lerpColor(primary, error, flash.value)
                drawPath(quadPath, edgeColor, style = Stroke(2.dp.toPx()))

                // Mid-edge pills, oriented along their edge.
                Edge.entries.forEach { edge ->
                    val a = pts[edge.a.ordinal]; val b = pts[edge.b.ordinal]
                    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                    val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                    val pill = Size(28.dp.toPx(), 8.dp.toPx())
                    val isActive = (activeHandle as? CropHandle.EdgeHandle)?.edge == edge
                    rotate(deg, mid) {
                        drawRoundRect(Color.White, Offset(mid.x - pill.width / 2, mid.y - pill.height / 2), pill, CornerRadius(pill.height / 2))
                        if (isActive) drawRoundRect(primary, Offset(mid.x - pill.width / 2, mid.y - pill.height / 2), pill, CornerRadius(pill.height / 2), style = Stroke(2.dp.toPx()))
                    }
                }
                // Corner handles.
                Corner.entries.forEach { c ->
                    val isActive = (activeHandle as? CropHandle.CornerHandle)?.corner == c
                    val center = pts[c.ordinal] + if (isActive) Offset(shake.value * 4.dp.toPx(), 0f) else Offset.Zero
                    val r = handleRadius * (if (isActive) handleScale else 1f)
                    drawCircle(Color.Black.copy(alpha = 0.25f), r + 2.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
                    drawCircle(Color.White, r, center)
                    drawCircle(primary, r, center, style = Stroke(2.dp.toPx()))
                }
            }

            // TalkBack: each corner is a focusable node with move actions (FR-20…24 without dragging).
            Corner.entries.forEach { c ->
                val p = quad[c]
                val name = stringResource(c.labelRes())
                val up = stringResource(R.string.crop_move_up)
                val down = stringResource(R.string.crop_move_down)
                val left = stringResource(R.string.crop_move_left)
                val right = stringResource(R.string.crop_move_right)
                Box(
                    Modifier
                        .offset { IntOffset((p.x * boxW).roundToInt() - 24.dp.roundToPx(), (p.y * boxH).roundToInt() - 24.dp.roundToPx()) }
                        .size(48.dp)
                        .semantics {
                            contentDescription = name
                            customActions = listOf(
                                CustomAccessibilityAction(up) { onNudge(c, NudgeDirection.UP); true },
                                CustomAccessibilityAction(down) { onNudge(c, NudgeDirection.DOWN); true },
                                CustomAccessibilityAction(left) { onNudge(c, NudgeDirection.LEFT); true },
                                CustomAccessibilityAction(right) { onNudge(c, NudgeDirection.RIGHT); true },
                            )
                        },
                )
            }
        }

        // Loupe: in screen space, top-left — jumping to top-right when the finger is in the left 40 %.
        val focus = activeHandle?.let { handlePoint(quad, it) }
        if (image != null && focus != null) {
            val center = Offset(cw / 2, ch / 2)
            val local = Offset((focus.x - 0.5f) * boxW, (focus.y - 0.5f) * boxH)
            val screen = center + local.rotateBy(rotation.degrees.toFloat())
            val onLeft = screen.x < cw * 0.4f
            val loupeX by animateFloatAsState(if (onLeft) 1f else 0f, tween(DeckleMotion.MEDIUM), label = "loupe")
            Loupe(
                image = image,
                focus = focus,
                quad = quad,
                boxSize = Size(boxW, boxH),
                degrees = rotation.degrees.toFloat(),
                crosshair = primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(((cw - 112.dp.toPx()) * loupeX).roundToInt(), 0) },
            )
        }
    }
}

@Composable
private fun Loupe(image: ImageBitmap, focus: PointN, quad: Quad, boxSize: Size, degrees: Float, crosshair: Color, modifier: Modifier) {
    Canvas(modifier.size(112.dp)) {
        val r = size.minDimension / 2
        val circle = Path().apply { addOval(Rect(Offset(r, r), r)) }
        clipPath(circle) {
            drawRect(Color.Black)
            val px = focus.x * boxSize.width
            val py = focus.y * boxSize.height
            val zoom = 2.5f
            withTransform({
                translate(r, r)
                rotate(degrees, Offset.Zero)
                scale(zoom, zoom, Offset.Zero)
                translate(-px, -py)
            }) {
                drawImage(image, dstSize = IntSize(boxSize.width.roundToInt(), boxSize.height.roundToInt()))
                // The two edges meeting here, so alignment is visible.
                val pts = quad.corners.map { Offset(it.x * boxSize.width, it.y * boxSize.height) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx() / zoom))
            }
        }
        val arm = 10.dp.toPx()
        drawLine(crosshair, Offset(r - arm, r), Offset(r + arm, r), 2.dp.toPx())
        drawLine(crosshair, Offset(r, r - arm), Offset(r, r + arm), 2.dp.toPx())
        drawCircle(Color.White, r - 1.5.dp.toPx(), Offset(r, r), style = Stroke(3.dp.toPx()))
    }
}

/** Nearest corner within reach wins; otherwise the nearest edge midpoint. */
private fun hitTest(quad: Quad, p: Offset, box: IntSize, reach: Float): CropHandle? {
    fun px(n: PointN) = Offset(n.x * box.width, n.y * box.height)
    val corner = Corner.entries.minBy { (px(quad[it]) - p).getDistance() }
    if ((px(quad[corner]) - p).getDistance() <= reach) return CropHandle.CornerHandle(corner)
    val edge = Edge.entries.minBy { e -> (midpoint(px(quad[e.a]), px(quad[e.b])) - p).getDistance() }
    return if ((midpoint(px(quad[edge.a]), px(quad[edge.b])) - p).getDistance() <= reach) CropHandle.EdgeHandle(edge) else null
}

private fun handlePoint(quad: Quad, handle: CropHandle): PointN = when (handle) {
    is CropHandle.CornerHandle -> quad[handle.corner]
    is CropHandle.EdgeHandle -> quad[handle.edge.a].let { a -> quad[handle.edge.b].let { b -> PointN((a.x + b.x) / 2, (a.y + b.y) / 2) } }
}

private fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)

private fun PointN.toOffset() = Offset(x, y)

private fun Offset.rotateBy(degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

private fun Corner.labelRes(): Int = when (this) {
    Corner.TOP_LEFT -> R.string.crop_corner_tl
    Corner.TOP_RIGHT -> R.string.crop_corner_tr
    Corner.BOTTOM_RIGHT -> R.string.crop_corner_br
    Corner.BOTTOM_LEFT -> R.string.crop_corner_bl
}

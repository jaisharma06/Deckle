package com.avyaan.deckle.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.avyaan.deckle.designsystem.theme.DeckleMotion
import com.avyaan.deckle.designsystem.theme.LocalReducedMotion
import com.avyaan.deckle.designsystem.theme.springSpec
import kotlinx.coroutines.launch

/**
 * Pinch 1–5×, double-tap 1× ⇄ 2.5× at the tap point, pan clamped to the content (spec 06 §7).
 * At 1× single-finger drags are left unconsumed so a surrounding pager can swipe.
 */
@Composable
fun Zoomable(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onZoomChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val size = remember { arrayOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size[0].width * (s - 1) / 2
        val maxY = size[0].height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .onSizeChanged { size[0] = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size > 1 || scale.value > 1.01f) {
                            val newScale = (scale.value * zoom).coerceIn(MIN, MAX)
                            scope.launch {
                                scale.snapTo(newScale)
                                offset.snapTo(clamp(offset.value + pan, newScale))
                            }
                            onZoomChanged(newScale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { p ->
                        val zoomIn = scale.value < 1.5f
                        val target = if (zoomIn) DOUBLE_TAP else 1f
                        // Keep the tapped point under the finger.
                        val center = Offset(size[0].width / 2f, size[0].height / 2f)
                        val targetOffset = if (zoomIn) clamp((center - p) * (target - 1), target) else Offset.Zero
                        onZoomChanged(zoomIn)
                        scope.launch { scale.animateTo(target, springSpec(DeckleMotion.Spring.Overlay, reduced)) }
                        scope.launch { offset.animateTo(targetOffset, springSpec(DeckleMotion.Spring.Overlay, reduced)) }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.value.x
                translationY = offset.value.y
            },
    ) { content() }
}

private const val MIN = 1f
private const val MAX = 5f
private const val DOUBLE_TAP = 2.5f

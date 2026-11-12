package com.avyaan.deckle.feature.capture.camera

import com.avyaan.deckle.domain.geometry.PointN
import kotlin.math.max

/**
 * Maps normalised points in the upright analysis frame to view pixels for a `FILL_CENTER` PreviewView
 * (spec 03 §2.2). FILL_CENTER scales the frame until it covers the view, then centres it, so some of
 * the frame is cropped on one axis.
 *
 * Pure and Android-free so it is unit-tested with rotated and cropped cases.
 */
data class PreviewMapping(val viewWidth: Float, val viewHeight: Float, val imageWidth: Float, val imageHeight: Float) {
    private val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    private val offsetX = (viewWidth - imageWidth * scale) / 2f
    private val offsetY = (viewHeight - imageHeight * scale) / 2f

    fun toViewX(p: PointN): Float = offsetX + p.x * imageWidth * scale
    fun toViewY(p: PointN): Float = offsetY + p.y * imageHeight * scale

    /** Inverse mapping, e.g. for tap-to-focus feedback. Not clamped: taps can land in the cropped margin. */
    fun toNormalized(viewX: Float, viewY: Float): PointN =
        PointN((viewX - offsetX) / (imageWidth * scale), (viewY - offsetY) / (imageHeight * scale))

    companion object {
        /** The analysis frame size after applying [rotationDegrees], i.e. as the user sees it. */
        fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
            if (rotationDegrees % 180 != 0) height to width else width to height
    }
}

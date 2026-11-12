package com.avyaan.deckle.data.imaging.framework

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.data.imaging.common.guarded
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.CornerOrdering
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.QuadGeometry
import com.avyaan.deckle.domain.geometry.QuadRotation
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.LumaFrame
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import javax.inject.Inject

/**
 * Degraded mode (D-5): used only when OpenCV's native library fails to load. Capture, manual crop and
 * export keep working; auto-detection and shadow flattening are unavailable.
 */

/** Null Object: "no page found", always. The capture UI needs no special-casing beyond [isAvailable]. */
public class NoOpPageDetector @Inject constructor() : PageDetector {
    override val isAvailable: Boolean = false
    override fun detect(frame: LumaFrame): Quad? = null
    override suspend fun detect(imagePath: String): Quad? = null
}

/** Full perspective warp with `Matrix.setPolyToPoly` (4 points = projective transform). */
public class FrameworkPerspectiveCorrector @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : PerspectiveCorrector {
    override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String): Outcome<ImageSize> =
        guarded(dispatchers) {
            val decoded = io.decodeFile(sourcePath) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
            val src = if (rotation == Rotation.R0) decoded
            else BitmapIO.transform(decoded, Matrix().apply { postRotate(rotation.degrees.toFloat()) })
            val q = CornerOrdering.order(QuadRotation.rotate(quad, rotation).corners)
            val size = ImageSize(src.width, src.height)
            val target = QuadGeometry.outputSize(q, size)

            val from = q.corners.flatMap { listOf(it.x * size.width, it.y * size.height) }.toFloatArray()
            val w = target.width.toFloat()
            val h = target.height.toFloat()
            val to = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val m = Matrix().apply { check(setPolyToPoly(from, 0, to, 0, 4)) { "Degenerate quad" } }

            val dst = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(dst).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                io.writeJpeg(dst, out, 95)
                Outcome.Success(target)
            } finally {
                dst.recycle()
                src.recycle()
            }
        }
}

/** ColorMatrix approximations of the four modes (no shadow flattening without morphology). */
public class FrameworkPageEnhancer @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : PageEnhancer {
    override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String): Outcome<ImageSize> =
        guarded(dispatchers) { render(basePath, mode, adjustments, out, null, 90) }

    override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String): Outcome<String> =
        guarded(dispatchers) {
            when (val r = render(basePath, mode, adjustments, out, longEdge, 85)) {
                is Outcome.Success -> Outcome.Success(out)
                is Outcome.Failure -> r
            }
        }

    override suspend fun ocrInput(basePath: String, out: String): Outcome<ImageSize> =
        guarded(dispatchers) { render(basePath, EnhancementMode.GREYSCALE, Adjustments.NEUTRAL, out, null, 92) }

    private fun render(path: String, mode: EnhancementMode, adj: Adjustments, out: String, longEdge: Int?, quality: Int): Outcome<ImageSize> {
        val src = io.decodeFile(path, longEdge) ?: return Outcome.Failure(DeckleError.ImageDecodeFailed)
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        try {
            val look = Look.of(mode)
            val cm = ColorMatrix().apply { setSaturation(look.saturation) }
            look.tint?.let { cm.postConcat(ColorMatrix(it)) }
            cm.postConcat(adjustmentMatrix(adj.copy(brightness = adj.brightness + look.brightness, contrast = adj.contrast + look.contrast)))
            Canvas(dst).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            look.thresholdInk?.let { threshold(dst, it) }
            io.writeJpeg(dst, out, quality)
            return Outcome.Success(ImageSize(dst.width, dst.height))
        } finally {
            src.recycle()
            dst.recycle()
        }
    }

    private fun adjustmentMatrix(adj: Adjustments): ColorMatrix {
        val a = 1f + adj.contrast * 0.6f
        val b = adj.brightness * 80f + (1 - a) * 128f
        return ColorMatrix(floatArrayOf(a, 0f, 0f, 0f, b, 0f, a, 0f, 0f, b, 0f, 0f, a, 0f, b, 0f, 0f, 0f, 1f, 0f))
    }

    /**
     * ColorMatrix approximation of each mode: saturation, an extra brightness/contrast offset, and an
     * optional threshold whose dark side is painted [thresholdInk] (0 = black).
     */
    private data class Look(
        val saturation: Float = 1f,
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val thresholdInk: Int? = null,
        /** Extra 4×5 colour matrix applied after saturation (tints, inversion). */
        val tint: FloatArray? = null,
    ) {
        companion object {
            fun of(mode: EnhancementMode): Look = when (mode) {
                EnhancementMode.ORIGINAL -> Look()
                EnhancementMode.MAGIC -> Look(saturation = 1.35f, brightness = 0.1f, contrast = 0.35f)
                EnhancementMode.COLOUR -> Look(saturation = 1.15f)
                EnhancementMode.LIGHTEN -> Look(brightness = 0.25f, contrast = 0.05f)
                EnhancementMode.PHOTO -> Look(saturation = 1.08f, contrast = 0.05f)
                EnhancementMode.GREYSCALE -> Look(saturation = 0f, contrast = 0.2f)
                EnhancementMode.BLACK_AND_WHITE -> Look(saturation = 0f, thresholdInk = 0)
                EnhancementMode.ECO -> Look(saturation = 0f, thresholdInk = 110)
                EnhancementMode.VIVID -> Look(saturation = 1.6f, contrast = 0.2f)
                EnhancementMode.WARM -> Look(tint = gains(1.1f, 1f, 0.86f))
                EnhancementMode.COOL -> Look(tint = gains(0.88f, 1f, 1.1f))
                EnhancementMode.SEPIA -> Look(tint = SEPIA)
                EnhancementMode.VINTAGE -> Look(tint = SEPIA, contrast = -0.25f)
                EnhancementMode.MATTE -> Look(saturation = 0.75f, contrast = -0.3f)
                EnhancementMode.NOIR -> Look(saturation = 0f, contrast = 0.5f)
                // No blur-based dodge without OpenCV: a crisp grey stands in for the sketch.
                EnhancementMode.SKETCH -> Look(saturation = 0f, contrast = 0.4f, brightness = 0.2f)
                EnhancementMode.BLUEPRINT -> Look(saturation = 0f, tint = BLUEPRINT)
                EnhancementMode.NEGATIVE -> Look(saturation = 0f, tint = NEGATIVE)
            }

            private fun gains(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            private val SEPIA = floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            private val NEGATIVE = floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)
            /** Grey in → blue (dark) to white (light): inverted, then mapped onto (20, 70, 140)…white. */
            private val BLUEPRINT = floatArrayOf(
                -(235f / 255f), 0f, 0f, 0f, 255f,
                0f, -(185f / 255f), 0f, 0f, 255f,
                0f, 0f, -(115f / 255f), 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
    }

    /** Global threshold at mean luminance; row by row to keep memory flat. */
    private fun threshold(bmp: Bitmap, ink: Int) {
        val inkColour = Color.rgb(ink, ink, ink)
        val row = IntArray(bmp.width)
        var sum = 0L
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (p in row) sum += p and 0xFF
        }
        val mean = (sum / (bmp.width.toLong() * bmp.height)).toInt()
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (i in row.indices) row[i] = if ((row[i] and 0xFF) > mean - 10) -0x1 else inkColour
            bmp.setPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
        }
    }
}

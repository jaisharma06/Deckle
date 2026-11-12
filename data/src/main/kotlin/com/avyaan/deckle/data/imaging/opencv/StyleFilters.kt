package com.avyaan.deckle.data.imaging.opencv

import javax.inject.Inject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/*
 * Style filters: looks rather than document clean-up. They take COLOUR (or GREY) input, so a paper page is
 * still shadow-flattened first and comes out clean, while photos and screenshots are styled as captured.
 */

/** Sepia: the classic brown-toned print. */
public class SepiaFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR
    override fun apply(scope: MatScope, src: Mat): Mat = scope.sepia(src)
}

/** Vintage: softened sepia, faded blacks and a gentle vignette, like an old photograph. */
public class VintageFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val toned = mat().also { Core.addWeighted(sepia(src), SEPIA_MIX, src, 1.0 - SEPIA_MIX, 0.0, it) }
        vignette(fade(toned, BLACK_LIFT, WHITE_CAP), VIGNETTE)
    }

    private companion object {
        const val SEPIA_MIX = 0.7
        const val BLACK_LIFT = 30.0
        const val WHITE_CAP = 235.0
        const val VIGNETTE = 0.3
    }
}

/** Warm: golden light — more red, less blue. */
public class WarmFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR
    override fun apply(scope: MatScope, src: Mat): Mat = scope.channelGains(src, blue = 0.86, green = 1.0, red = 1.1)
}

/** Cool: crisp daylight — more blue, less red. */
public class CoolFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR
    override fun apply(scope: MatScope, src: Mat): Mat = scope.channelGains(src, blue = 1.1, green = 1.0, red = 0.88)
}

/** Vivid: punchy colour and local contrast, for colourful material (brochures, drawings, charts). */
public class VividFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        scaleSaturation(claheLightness(src, CLAHE_CLIP), SATURATION)
    }

    private companion object {
        const val CLAHE_CLIP = 3.0
        const val SATURATION = 1.6
    }
}

/** Matte: lifted blacks, softened whites and muted colour — a calm, printed-on-uncoated-paper look. */
public class MatteFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        scaleSaturation(fade(src, BLACK_LIFT, WHITE_CAP), SATURATION)
    }

    private companion object {
        const val BLACK_LIFT = 40.0
        const val WHITE_CAP = 238.0
        const val SATURATION = 0.75
    }
}

/** Noir: dramatic high-contrast black and white with real greys (unlike the thresholded B&W). */
public class NoirFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val stretched = mat().also { Core.normalize(src, it, 0.0, 255.0, Core.NORM_MINMAX) }
        // S-curve: gamma darkens mid-tones, then levels push the ends apart.
        levels(gamma(stretched, GAMMA), BLACK, WHITE)
    }

    private companion object {
        const val GAMMA = 1.35
        const val BLACK = 25.0
        const val WHITE = 225.0
    }
}

/** Negative: inverted grey, for reading in the dark or spotting faint pencil. */
public class NegativeFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        mat().also { Core.bitwise_not(src, it) }
    }
}

/** Sketch: a pencil drawing — the "colour dodge" of grey over its own blurred negative. */
public class SketchFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val inverted = mat().also { Core.bitwise_not(src, it) }
        val sigma = maxOf(src.cols(), src.rows()) * BLUR_FRACTION
        val blurred = mat().also { Imgproc.GaussianBlur(inverted, it, Size(0.0, 0.0), sigma.coerceAtLeast(1.0)) }
        val blurredInv = mat().also { Core.bitwise_not(blurred, it) }
        // dodge = grey · 256 / (255 − blur(¬grey)); saturates to white on flat areas.
        val greyF = mat().also { src.convertTo(it, CvType.CV_32F) }
        val denominator = mat().also { blurredInv.convertTo(it, CvType.CV_32F, 1.0, 1.0) }
        val dodged = mat().also { Core.divide(greyF, denominator, it, 256.0) }
        mat().also { dodged.convertTo(it, CvType.CV_8U) }
    }

    private companion object {
        const val BLUR_FRACTION = 0.008
    }
}

/** Blueprint: white lines on engineering blue. */
public class BlueprintFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val ink = mat().also { Core.bitwise_not(src, it) }        // strokes bright, paper dark
        val strength = mat().also { ink.convertTo(it, CvType.CV_32F, 1.0 / 255.0) }
        // Mix per channel: blue background (B 140, G 70, R 20) → white where ink is.
        val channels = listOf(BLUE_B, BLUE_G, BLUE_R).map { base ->
            mat().also { strength.convertTo(it, CvType.CV_8U, 255.0 - base, base) }
        }
        mat().also { Core.merge(channels, it) }
    }

    private companion object {
        const val BLUE_B = 140.0
        const val BLUE_G = 70.0
        const val BLUE_R = 20.0
    }
}

/** Sepia colour matrix (BGR order). White maps to a warm cream, black stays black. */
internal fun MatScope.sepia(bgr: Mat): Mat {
    val kernel = Mat(3, 3, CvType.CV_32F).track().apply {
        put(0, 0, 0.131, 0.534, 0.272)   // B' from B, G, R
        put(1, 0, 0.168, 0.686, 0.349)   // G'
        put(2, 0, 0.189, 0.769, 0.393)   // R'
    }
    return mat().also { Core.transform(bgr, it, kernel) }
}

/** Per-channel gains (saturating). */
internal fun MatScope.channelGains(bgr: Mat, blue: Double, green: Double, red: Double): Mat {
    val kernel = Mat(3, 3, CvType.CV_32F).track().apply {
        put(0, 0, blue, 0.0, 0.0)
        put(1, 0, 0.0, green, 0.0)
        put(2, 0, 0.0, 0.0, red)
    }
    return mat().also { Core.transform(bgr, it, kernel) }
}

/** Compresses the tonal range into [black]…[white]: lifted blacks, softened highlights. */
internal fun MatScope.fade(src: Mat, black: Double, white: Double): Mat =
    mat().also { src.convertTo(it, -1, (white - black) / 255.0, black) }

/** Darkens towards the corners; [strength] is the fraction of brightness lost at the very corner. */
internal fun MatScope.vignette(bgr: Mat, strength: Double): Mat {
    val w = bgr.cols()
    val h = bgr.rows()
    // Built small and scaled up: the falloff is smooth, so 64 px of resolution is plenty.
    val small = Mat(MASK_EDGE, MASK_EDGE, CvType.CV_32F).track()
    val row = FloatArray(MASK_EDGE)
    for (y in 0 until MASK_EDGE) {
        val dy = (y + 0.5) / MASK_EDGE * 2 - 1
        for (x in 0 until MASK_EDGE) {
            val dx = (x + 0.5) / MASK_EDGE * 2 - 1
            val d2 = ((dx * dx + dy * dy) / 2).coerceIn(0.0, 1.0)
            row[x] = (1.0 - strength * d2 * d2).toFloat()
        }
        small.put(y, 0, row)
    }
    val mask = mat().also { Imgproc.resize(small, it, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) }
    val mask3 = mat().also { Core.merge(listOf(mask, mask, mask), it) }
    val bgrF = mat().also { bgr.convertTo(it, CvType.CV_32FC3) }
    val shaded = mat().also { Core.multiply(bgrF, mask3, it) }
    return mat().also { shaded.convertTo(it, CvType.CV_8UC3) }
}

private const val MASK_EDGE = 64

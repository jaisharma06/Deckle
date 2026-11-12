package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.domain.imaging.EnhancementMode
import dagger.MapKey
import javax.inject.Inject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Strategy: one class per enhancement mode (FR-31). Why: interchangeable algorithms switched at runtime,
 * each independently testable; adding a mode is a new class plus one map binding, with no edits to
 * existing filters or to the enhancer.
 */
public interface EnhancementFilter {
    /** What the enhancer must hand this filter. */
    public val input: Input

    /** Returns a Mat tracked by [scope]. Must not release [src]. */
    public fun apply(scope: MatScope, src: Mat): Mat

    public enum class Input {
        /**
         * BGR after per-channel shadow flattening, so paper is already near-white. Flattening only runs on
         * pages that look like paper ([PaperDetector]); anything else arrives as captured.
         */
        COLOUR,
        /** Single-channel grey, shadow-flattened when the page looks like paper. */
        GREY,
        /**
         * BGR exactly as captured. For modes where flattening would hurt: it normalises away smooth
         * gradients, which is right for paper but wrong for photos and illustrations.
         */
        RAW,
    }
}

@MapKey
@Retention(AnnotationRetention.RUNTIME)
public annotation class EnhancementModeKey(val value: EnhancementMode)

/**
 * Original: the cropped, straightened capture exactly as photographed. No flattening: it used to flatten
 * shadows, which whitened coloured backgrounds and outlined light text (screenshots, posters, photos).
 */
public class OriginalFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.RAW
    override fun apply(scope: MatScope, src: Mat): Mat = src
}

/**
 * Magic: the "scanned on a flatbed" look. Levels push near-white paper to pure white and faint ink
 * darker, an unsharp mask crisps strokes, and a saturation lift keeps stamps and highlighter vivid.
 */
public class MagicFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val levelled = levels(src, INK_POINT, PAPER_POINT)
        val sharp = unsharp(levelled, SHARPEN_SIGMA, SHARPEN_AMOUNT)
        scaleSaturation(sharp, SATURATION)
    }

    private companion object {
        const val INK_POINT = 30.0
        const val PAPER_POINT = 215.0
        const val SHARPEN_SIGMA = 1.5
        const val SHARPEN_AMOUNT = 0.8
        const val SATURATION = 1.35
    }
}

/** Colour: CLAHE on L in Lab, then a mild saturation lift. */
public class ColourFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.COLOUR

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        scaleSaturation(claheLightness(src, CLAHE_CLIP), SATURATION_LIFT)
    }

    private companion object {
        const val CLAHE_CLIP = 2.0
        const val SATURATION_LIFT = 1.15
    }
}

/**
 * Lighten: for dim, unevenly lit captures (evening light, no flash, a lamp to one side). Neutralises
 * the indoor colour cast, evens out the lighting, lifts mid-tones, then auto-levels so the text stays
 * black. Unlike the flattening modes, photos and colour blocks on the page survive.
 */
public class LightenFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.RAW

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val evened = evenLighting(greyWorld(src), BLUR_FRACTION, PAPER_TARGET)
        autoLevels(gamma(evened, GAMMA), LEVELS_CLIP)
    }

    private companion object {
        /** Illumination blur radius as a fraction of the page's long edge (tuned: 1/12 leaves halos round photos). */
        const val BLUR_FRACTION = 1.0 / 8
        const val PAPER_TARGET = 235.0
        const val GAMMA = 0.85
        const val LEVELS_CLIP = 0.01
    }
}

/**
 * Photo: for photographs, ID cards and artwork, where shadow flattening would erase gradients.
 * Only global corrections: white balance, auto-levels and a slight colour lift.
 */
public class PhotoFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.RAW

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        scaleSaturation(autoLevels(greyWorld(src), LEVELS_CLIP), SATURATION)
    }

    private companion object {
        const val LEVELS_CLIP = 0.005
        const val SATURATION = 1.08
    }
}

/** Greyscale: contrast-normalised grey. */
public class GreyscaleFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        mat().also { Core.normalize(src, it, 0.0, 255.0, Core.NORM_MINMAX) }
    }
}

/** Black & white: adaptive Gaussian threshold, block 21, C 10 — tuned for text and whiteboards. */
public class BlackWhiteFilter @Inject constructor() : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY
    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        mat().also {
            Imgproc.adaptiveThreshold(src, it, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, BLOCK, C)
        }
    }

    private companion object {
        const val BLOCK = 21
        const val C = 10.0
    }
}

/**
 * Ink saver: black & white with ink at mid-grey instead of black, so printing uses roughly half the
 * toner while text stays legible. Reuses [BlackWhiteFilter] for the thresholding.
 */
public class EcoFilter @Inject constructor(private val blackWhite: BlackWhiteFilter) : EnhancementFilter {
    override val input: EnhancementFilter.Input = EnhancementFilter.Input.GREY

    override fun apply(scope: MatScope, src: Mat): Mat = with(scope) {
        val bw = blackWhite.apply(this, src)
        // 0 → INK, 255 → 255.
        mat().also { bw.convertTo(it, -1, (255.0 - INK) / 255.0, INK) }
    }

    private companion object {
        const val INK = 110.0
    }
}

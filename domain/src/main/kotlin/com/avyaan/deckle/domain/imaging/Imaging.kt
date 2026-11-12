package com.avyaan.deckle.domain.imaging

import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable

/**
 * FR-31. The order is the order shown in the filter strip. Persisted by name, so new values may be
 * inserted anywhere; never rename one.
 */
public enum class EnhancementMode {
    ORIGINAL,
    /** Whitened paper, vivid ink, sharpened text. */
    MAGIC,
    COLOUR,
    /** Brightens a dim capture without shadow flattening. */
    LIGHTEN,
    /** No shadow flattening: white balance and levels only, for photos, IDs and artwork. */
    PHOTO,
    /** Punchy colour and local contrast. */
    VIVID,
    WARM,
    COOL,
    SEPIA,
    /** Softened sepia, faded blacks, vignette. */
    VINTAGE,
    /** Lifted blacks, muted colour. */
    MATTE,
    GREYSCALE,
    /** High-contrast grey with real mid-tones. */
    NOIR,
    BLACK_AND_WHITE,
    /** Black & white with mid-grey ink, to save toner when printing. */
    ECO,
    /** Pencil drawing. */
    SKETCH,
    /** White lines on engineering blue. */
    BLUEPRINT,
    /** Inverted grey. */
    NEGATIVE,
}

/** FR-33. Each value in [-1, 1]; 0 is neutral. */
@Serializable
public data class Adjustments(val brightness: Float = 0f, val contrast: Float = 0f) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f

    public companion object {
        public val NEUTRAL: Adjustments = Adjustments()
    }
}

/**
 * A greyscale camera frame: the Y plane of a YUV_420_888 image, wrapped without copying.
 * `java.nio.ByteBuffer` is plain JVM, so this keeps the detector port free of Android types.
 */
public class LumaFrame(
    public val buffer: ByteBuffer,
    public val width: Int,
    public val height: Int,
    public val rowStride: Int,
    /** Clockwise rotation needed to make the frame upright. */
    public val rotationDegrees: Int,
)

/** Where an image to normalise comes from. */
public sealed interface ImageInput {
    public class JpegBytes(public val bytes: ByteArray) : ImageInput
    public data class FilePath(val path: String) : ImageInput
    public data class ContentUri(val uri: String) : ImageInput
}

/** Finds a page outline. Implementations must never throw; "no page" is `null`. */
public interface PageDetector {
    /** False for the no-op detector used when OpenCV is unavailable (degraded mode). */
    public val isAvailable: Boolean

    /** Live lane. Returns a quad normalised against the *upright* frame. */
    public fun detect(frame: LumaFrame): Quad?

    /** Still lane: decodes a small copy of the image internally. */
    public suspend fun detect(imagePath: String): Quad?
}

/** Decodes, applies EXIF orientation, downscales to ≤ 3000 px and rewrites without EXIF (also strips GPS, FR-81). */
public interface ImageNormalizer {
    public suspend fun normalize(
        input: ImageInput,
        sourceOut: String,
        previewOut: String,
        previewLongEdge: Int = PREVIEW_LONG_EDGE,
    ): Outcome<ImageSize>

    public companion object {
        public const val PREVIEW_LONG_EDGE: Int = 1600
    }
}

/** Rotation, then perspective correction ("dewarp"). Writes the base image. */
public interface PerspectiveCorrector {
    public suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String): Outcome<ImageSize>
}

/** Shadow flattening + enhancement mode + adjustments. Always works from the base image (FR-31). */
public interface PageEnhancer {
    public suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String): Outcome<ImageSize>

    /** FR-32: fast, downscaled render for on-screen preview. Returns the written path. */
    public suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String): Outcome<String>

    /** FR-60: shadow-flattened greyscale for OCR, never thresholded. */
    public suspend fun ocrInput(basePath: String, out: String): Outcome<ImageSize>
}

/** Rotates an image file by quarter turns in place-safe fashion (writes to [out]). */
public interface ImageRotator {
    public suspend fun rotate(path: String, rotation: Rotation, out: String): Outcome<ImageSize>
}

public interface ThumbnailMaker {
    public suspend fun make(imagePath: String, out: String, longEdge: Int = THUMB_LONG_EDGE): Outcome<Unit>

    public companion object {
        public const val THUMB_LONG_EDGE: Int = 320
    }
}

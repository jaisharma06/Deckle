package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.data.imaging.common.guarded
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.PageEnhancer
import javax.inject.Inject
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Fixed-shape pipeline: base → shadow flattening (unless the filter asks for RAW input) → mode filter →
 * brightness/contrast.
 * Always starts from the base image, never from an already-enhanced one (FR-31).
 */
public class OpenCvPageEnhancer @Inject constructor(
    private val filters: Map<EnhancementMode, @JvmSuppressWildcards EnhancementFilter>,
    private val dispatchers: DispatcherProvider,
) : PageEnhancer {

    override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String): Outcome<ImageSize> =
        guarded(dispatchers) { render(basePath, mode, adjustments, out, longEdge = null, quality = PROCESSED_QUALITY) }

    /** FR-32: same chain on a downscaled copy, on the preview dispatcher so it doesn't queue behind full-res work. */
    override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String): Outcome<String> =
        withContext(dispatchers.preview) {
            try {
                when (val r = render(basePath, mode, adjustments, out, longEdge, PREVIEW_QUALITY)) {
                    is Outcome.Success -> Outcome.Success(out)
                    is Outcome.Failure -> r
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failure(DeckleError.Unexpected(e))
            }
        }

    /** FR-60: flattened grey, never thresholded (thresholding destroys stroke detail OCR needs). */
    override suspend fun ocrInput(basePath: String, out: String): Outcome<ImageSize> = guarded(dispatchers) {
        matScope {
            val colour = Imgcodecs.imread(basePath, Imgcodecs.IMREAD_COLOR).track()
            if (colour.empty()) return@matScope Outcome.Failure(DeckleError.ImageDecodeFailed)
            val base = mat().also { Imgproc.cvtColor(colour, it, Imgproc.COLOR_BGR2GRAY) }
            // Light text on a dark or coloured background would come out of flattening as outlined glyphs.
            val flat = if (PaperDetector.looksLikePaper(this, colour)) ShadowFlattener.flattenChannel(this, base) else base
            writeJpeg(flat, out, OCR_QUALITY)
            Outcome.Success(ImageSize(flat.cols(), flat.rows()))
        }
    }

    private fun render(basePath: String, mode: EnhancementMode, adj: Adjustments, out: String, longEdge: Int?, quality: Int): Outcome<ImageSize> =
        matScope {
            val filter = filters.getValue(mode)
            val loaded = Imgcodecs.imread(basePath, Imgcodecs.IMREAD_COLOR).track()
            if (loaded.empty()) return@matScope Outcome.Failure(DeckleError.ImageDecodeFailed)
            val base = longEdge?.let { downscale(loaded, it) } ?: loaded

            // Decided on the full page even for previews, so a thumbnail never disagrees with the result.
            val paper = filter.input != EnhancementFilter.Input.RAW && PaperDetector.looksLikePaper(this, loaded)
            val flat = when (filter.input) {
                EnhancementFilter.Input.COLOUR -> if (paper) ShadowFlattener.flattenColour(this, base) else base
                EnhancementFilter.Input.GREY -> {
                    val grey = mat().also { Imgproc.cvtColor(base, it, Imgproc.COLOR_BGR2GRAY) }
                    if (paper) ShadowFlattener.flattenChannel(this, grey) else grey
                }
                EnhancementFilter.Input.RAW -> base
            }
            val filtered = filter.apply(this, flat)
            val adjusted = adjust(filtered, adj)
            writeJpeg(adjusted, out, quality)
            Outcome.Success(ImageSize(adjusted.cols(), adjusted.rows()))
        }

    /** FR-33: dst = src·α + β, α ∈ [0.4, 1.6], β ∈ [−80, 80]. */
    private fun MatScope.adjust(src: Mat, adj: Adjustments): Mat {
        if (adj.isNeutral) return src
        val alpha = 1.0 + adj.contrast * 0.6
        val beta = adj.brightness * 80.0 + (1 - alpha) * 128.0   // contrast pivots around mid-grey
        return mat().also { src.convertTo(it, -1, alpha, beta) }
    }

    private fun MatScope.downscale(src: Mat, longEdge: Int): Mat {
        val long = maxOf(src.cols(), src.rows())
        if (long <= longEdge) return src
        val s = longEdge.toDouble() / long
        return mat().also { Imgproc.resize(src, it, Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private companion object {
        const val PROCESSED_QUALITY = 90
        const val PREVIEW_QUALITY = 85
        const val OCR_QUALITY = 92
    }
}

/**
 * Decides whether shadow flattening is safe. Flattening keeps only what differs from the local background,
 * which is exactly right for ink on paper and destructive for anything else: a coloured or dark background
 * turns white and light text turns into dark outlines. So it runs only when most of the page's background
 * (estimated like the flattener does, on a small copy) is reasonably bright and nearly colourless.
 * Thresholds measured on real captures: paper bills score ~0.99, a gradient screenshot ~0.01.
 */
internal object PaperDetector {
    private const val WORK_EDGE = 256.0
    /** HSV value floor: low enough that paper photographed in dim light still counts. */
    private const val MIN_VALUE = 90.0
    /** HSV saturation ceiling (0…255): warm lamplight on paper stays under it, coloured backgrounds don't. */
    private const val MAX_SATURATION = 70.0
    private const val MIN_PAPER_FRACTION = 0.6

    fun looksLikePaper(scope: MatScope, bgr: Mat): Boolean = with(scope) {
        val s = WORK_EDGE / maxOf(bgr.cols(), bgr.rows())
        val small = if (s >= 1.0) bgr else mat().also { Imgproc.resize(bgr, it, Size(bgr.cols() * s, bgr.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA) }
        // Background estimate: close over thin dark strokes (text), then smooth.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).track()
        val dilated = mat().also { Imgproc.dilate(small, it, kernel) }
        val background = mat().also { Imgproc.medianBlur(dilated, it, 7) }
        val hsv = mat().also { Imgproc.cvtColor(background, it, Imgproc.COLOR_BGR2HSV) }
        val mask = mat().also { Core.inRange(hsv, Scalar(0.0, 0.0, MIN_VALUE), Scalar(180.0, MAX_SATURATION, 255.0), it) }
        Core.countNonZero(mask) >= MIN_PAPER_FRACTION * mask.total()
    }
}

/**
 * Removes the gradient cast by the phone's own shadow (requirements: "Shadow flattening"):
 * dilate 9×9 → median 21 = background estimate → |src − bg| → invert → stretch.
 */
internal object ShadowFlattener {
    private const val DILATE = 9.0
    private const val MEDIAN = 21

    fun flattenChannel(scope: MatScope, channel: Mat): Mat = with(scope) {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(DILATE, DILATE)).track()
        val dilated = mat().also { Imgproc.dilate(channel, it, kernel) }
        val background = mat().also { Imgproc.medianBlur(dilated, it, MEDIAN) }
        val diff = mat().also { Core.absdiff(channel, background, it) }
        val inverted = mat().also { Core.bitwise_not(diff, it) }
        mat().also { Core.normalize(inverted, it, 0.0, 255.0, Core.NORM_MINMAX) }
    }

    /** Colour modes flatten each BGR channel independently. */
    fun flattenColour(scope: MatScope, bgr: Mat): Mat = with(scope) {
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        channels.forEach { it.track() }
        val flat = channels.map { flattenChannel(this, it) }
        mat().also { Core.merge(flat, it) }
    }
}

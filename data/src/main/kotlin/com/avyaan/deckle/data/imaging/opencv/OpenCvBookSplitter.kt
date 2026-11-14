package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.data.imaging.common.guarded
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.imaging.BookSplitter
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * NV-12 on a straightened spread.
 *
 * Spine: the gutter is the darkest vertical band near the middle (the paper curls away from the light there).
 *
 * Curl: near the spine the page bends down into the gutter, so its top and bottom edges dip toward the middle
 * of the image and the lines of text bend with them. Each half's top and bottom page edges are traced column by
 * column (page = bright, background = dark after Otsu), smoothed with a cubic fit, and every column is then
 * stretched so both edges become straight. That straightens text lines in proportion to the edge curve, which
 * is the bulk of the visible bend. When the edges can't be seen (the page fills the photo, or the background is
 * as light as the paper), the half is only split, never distorted.
 */
public class OpenCvBookSplitter @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : BookSplitter {

    override suspend fun split(spreadPath: String, leftOut: String, rightOut: String): Outcome<Unit> = guarded(dispatchers) {
        matScope {
            val img = Imgcodecs.imread(spreadPath, Imgcodecs.IMREAD_COLOR).track()
            if (img.empty()) return@matScope Outcome.Failure(DeckleError.ImageDecodeFailed)
            val spine = findSpine(img)
            val left = img.submat(Rect(0, 0, spine, img.rows())).track()
            val right = img.submat(Rect(spine, 0, img.cols() - spine, img.rows())).track()
            writeJpeg(flatten(left), leftOut, QUALITY)
            writeJpeg(flatten(right), rightOut, QUALITY)
            Outcome.Success(Unit)
        }
    }

    /** Column of the gutter, in full-resolution pixels. */
    internal fun MatScope.findSpine(img: Mat): Int {
        val small = toGreyWork(img)
        val w = small.cols()
        val h = small.rows()
        // Mean brightness of each column over the middle 80 % of the height (ignores the page edges).
        val band = small.submat(Rect(0, (h * 0.1).toInt(), w, (h * 0.8).toInt())).track()
        val profile = mat().also { Core.reduce(band, it, 0, Core.REDUCE_AVG, CvType.CV_32F) }
        val values = FloatArray(w).also { profile.get(0, 0, it) }
        val smooth = FloatArray(w) { x -> (maxOf(0, x - 4)..minOf(w - 1, x + 4)).map { values[it] }.average().toFloat() }
        val from = (w * SPINE_SEARCH_FROM).toInt()
        val to = (w * (1 - SPINE_SEARCH_FROM)).toInt()
        // A slight pull toward the centre so a dark photo on one page doesn't win over a faint gutter.
        val best = (from..to).minBy { x -> smooth[x] + CENTRE_PULL * abs(x - w / 2f) / w * 255f }
        return (best.toDouble() / w * img.cols()).roundToInt().coerceIn(1, img.cols() - 1)
    }

    /** Straightens the top and bottom page edges of one half (see the class comment). */
    private fun MatScope.flatten(half: Mat): Mat {
        val work = toGreyWork(half)
        val scale = half.cols().toDouble() / work.cols()
        val blurred = mat().also { Imgproc.GaussianBlur(work, it, Size(5.0, 5.0), 0.0) }
        val mask = mat().also { Imgproc.threshold(blurred, it, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU) }
        // Close the text so the page reads as one bright region.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0)).track()
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val w = mask.cols()
        val h = mask.rows()
        val px = ByteArray(w * h).also { mask.get(0, 0, it) }
        fun page(x: Int, y: Int) = px[y * w + x].toInt() != 0
        val run = maxOf(3, h / 100)
        val tops = DoubleArray(w) { Double.NaN }
        val bottoms = DoubleArray(w) { Double.NaN }
        for (x in 0 until w) {
            tops[x] = (0 until (h * EDGE_BAND).toInt()).firstOrNull { y -> (0 until run).all { page(x, y + it) } }?.toDouble() ?: Double.NaN
            bottoms[x] = ((h * (1 - EDGE_BAND)).toInt() until h).reversed().firstOrNull { y -> (0 until run).all { page(x, y - it) } }?.toDouble() ?: Double.NaN
        }
        val top = fitEdge(tops, 0.0) ?: return half
        val bottom = fitEdge(bottoms, h - 1.0) ?: return half
        // Nothing to undo: both edges already straight (within half a percent of the height).
        if (spread(top) < h * 0.005 && spread(bottom) < h * 0.005) return half

        val outH = half.rows()
        val mapX = Mat(outH, half.cols(), CvType.CV_32F).track()
        val mapY = Mat(outH, half.cols(), CvType.CV_32F).track()
        val rowX = FloatArray(half.cols()) { it.toFloat() }
        val col = FloatArray(half.cols())
        for (y in 0 until outH) {
            val t = y.toDouble() / (outH - 1)
            for (x in 0 until half.cols()) {
                val wx = (x / scale).toInt().coerceIn(0, w - 1)
                val src = top[wx] + t * (bottom[wx] - top[wx])
                col[x] = (src * scale).toFloat()
            }
            mapX.put(y, 0, rowX)
            mapY.put(y, 0, col)
        }
        return mat().also { Imgproc.remap(half, it, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
    }

    /**
     * A smooth cubic through the traced edge points, with outliers (text touching the edge, a finger)
     * dropped once. Null when too few columns found the edge. Columns with no edge sit at [fallback].
     */
    private fun fitEdge(points: DoubleArray, fallback: Double): DoubleArray? {
        val n = points.size
        var xs = points.indices.filter { !points[it].isNaN() }
        if (xs.size < n * MIN_EDGE_COVERAGE) return if (xs.isEmpty()) DoubleArray(n) { fallback } else null
        var coeffs = Polyfit.cubic(xs.map { it.toDouble() / n }, xs.map { points[it] }) ?: return null
        val residuals = xs.map { abs(Polyfit.eval(coeffs, it.toDouble() / n) - points[it]) }
        val limit = 2.5 * (residuals.sorted()[residuals.size / 2] + 1.0)
        xs = xs.filterIndexed { i, _ -> residuals[i] <= limit }
        coeffs = Polyfit.cubic(xs.map { it.toDouble() / n }, xs.map { points[it] }) ?: return null
        return DoubleArray(n) { Polyfit.eval(coeffs, it.toDouble() / n) }
    }

    private fun spread(curve: DoubleArray) = curve.max() - curve.min()

    private fun MatScope.toGreyWork(img: Mat): Mat {
        val grey = mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2GRAY) }
        val s = WORK_WIDTH / img.cols()
        if (s >= 1) return grey
        return mat().also { Imgproc.resize(grey, it, Size(img.cols() * s, img.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private companion object {
        const val QUALITY = 95
        const val WORK_WIDTH = 600.0
        /** The gutter is searched between 38 % and 62 % of the width. */
        const val SPINE_SEARCH_FROM = 0.38
        const val CENTRE_PULL = 0.6f
        /** Page edges are looked for in the outer 30 % at the top and bottom. */
        const val EDGE_BAND = 0.3
        /** At least this share of columns must show the edge to trust a fit. */
        const val MIN_EDGE_COVERAGE = 0.5
    }
}

/** Least-squares cubic fit, small enough to keep here rather than pull in a maths library. */
internal object Polyfit {
    fun cubic(x: List<Double>, y: List<Double>): DoubleArray? {
        if (x.size < 4) return null
        // Normal equations (XᵀX) c = Xᵀy for c0 + c1 x + c2 x² + c3 x³.
        val a = Array(4) { DoubleArray(5) }
        for (i in x.indices) {
            val p = DoubleArray(4) { k -> Math.pow(x[i], k.toDouble()) }
            for (r in 0 until 4) {
                for (c in 0 until 4) a[r][c] += p[r] * p[c]
                a[r][4] += p[r] * y[i]
            }
        }
        // Gauss-Jordan with partial pivoting.
        for (col in 0 until 4) {
            val pivot = (col until 4).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until 4) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (c in col..4) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(4) { a[it][4] / a[it][it] }
    }

    fun eval(c: DoubleArray, x: Double): Double = c[0] + x * (c[1] + x * (c[2] + x * c[3]))
}

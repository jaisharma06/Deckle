package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.geometry.CornerOrdering
import com.avyaan.deckle.domain.geometry.DetectionTuning
import com.avyaan.deckle.domain.geometry.PointN
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.QuadGeometry
import com.avyaan.deckle.domain.geometry.QuadRules
import com.avyaan.deckle.domain.imaging.LumaFrame
import com.avyaan.deckle.domain.imaging.PageDetector
import javax.inject.Inject
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Finds the page outline. Several edge views of the same frame are searched, because no single one works
 * everywhere: fixed Canny thresholds see nothing in dim light, low thresholds drown in texture, and a
 * brightness split (Otsu) finds a pale page on a dark table but not on a pale one. In every view the largest
 * contours are reduced to four corners (convex hull, then progressively coarser polygon fits, so a finger or
 * a curled corner doesn't break it), checked with [QuadRules], and scored by how much of their outline sits
 * on real image edges. The best-scoring quad wins; null rather than a bad quad.
 *
 * Tuned against the on-device corpus in DetectorCorpusTest (dim, low contrast, small, occluded pages).
 */
public class OpenCvPageDetector @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : PageDetector {
    override val isAvailable: Boolean = true

    override fun detect(frame: LumaFrame): Quad? = try {
        matScope {
            // Zero-copy wrap of the Y plane; rowStride may exceed width.
            val luma = Mat(frame.height, frame.width, CvType.CV_8UC1, frame.buffer, frame.rowStride.toLong()).track()
            val small = resizeToLongEdge(luma, DetectionTuning.ANALYSIS_LONG_EDGE_PX)
            val upright = rotateUpright(small, frame.rotationDegrees)
            findQuad(upright)
        }
    } catch (e: Exception) {
        logger.warn(TAG, "Live detection failed", e)
        null
    }

    override suspend fun detect(imagePath: String): Quad? = withContext(dispatchers.imaging) {
        try {
            matScope {
                val grey = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE).track()
                if (grey.empty()) return@matScope null
                findQuad(resizeToLongEdge(grey, DetectionTuning.ANALYSIS_LONG_EDGE_PX))
            }
        } catch (e: Exception) {
            logger.warn(TAG, "Still detection failed", e)
            null
        }
    }

    private fun MatScope.findQuad(grey: Mat): Quad? {
        val w = grey.cols()
        val h = grey.rows()
        val blurred = mat().also { Imgproc.GaussianBlur(grey, it, Size(5.0, 5.0), 0.0) }
        // Closing erases dark print, so the page's interior is flat and its outline is the strongest edge.
        val flat = mat().also { Imgproc.morphologyEx(blurred, it, Imgproc.MORPH_CLOSE, rect(TEXT_ERASE_PX)) }
        val support = EdgeSupport(dilated(canny(flat, WEAK_LOW, WEAK_HIGH), SUPPORT_BAND_PX), w, h)

        var best: Quad? = null
        var bestScore = 0f
        for (edges in edgeViews(flat)) {
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(dilated(edges, 3), contours, mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.forEach { it.track() }
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(MAX_CANDIDATES)) {
                val corners = fourCorners(contour) ?: continue
                val quad = CornerOrdering.order(
                    corners.map { PointN((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) },
                )
                val area = QuadGeometry.areaFraction(quad)
                if (area > MAX_AREA_FRACTION || !QuadRules.isPlausiblePage(quad, aspect = w.toFloat() / h)) continue
                val onEdges = support.of(quad)
                if (onEdges < DetectionTuning.MIN_EDGE_SUPPORT) continue
                // Mostly edge support; area breaks ties in favour of the whole page over a panel inside it.
                val score = onEdges * (AREA_BIAS + area)
                if (score > bestScore) {
                    best = quad
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun MatScope.edgeViews(flat: Mat): Sequence<Mat> = sequence {
        yield(canny(flat, 75.0, 200.0)) // crisp page on a contrasting surface
        val median = median(flat)
        yield(canny(flat, maxOf(5.0, 0.335 * median), maxOf(15.0, 0.665 * median))) // scaled to the light level
        yield(canny(flat, 20.0, 60.0)) // soft, low-contrast outlines
        val split = mat().also { Imgproc.threshold(flat, it, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU) }
        val solid = mat().also { Imgproc.morphologyEx(split, it, Imgproc.MORPH_CLOSE, rect(TEXT_ERASE_PX)) }
        yield(mat().also { Imgproc.morphologyEx(solid, it, Imgproc.MORPH_GRADIENT, rect(3)) }) // bright region's rim
    }

    /** Four corners from a contour: its convex hull, simplified until exactly four points remain. */
    private fun MatScope.fourCorners(contour: MatOfPoint): List<Point>? {
        val hullIdx = MatOfInt().track()
        Imgproc.convexHull(contour, hullIdx)
        val points = contour.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { points[it] }.toTypedArray()).track()
        val perimeter = Imgproc.arcLength(hull, true)
        for (eps in EPSILON_STEPS) {
            val approx = MatOfPoint2f().track()
            Imgproc.approxPolyDP(hull, approx, eps * perimeter, true)
            when {
                approx.rows() == 4 -> return approx.toArray().toList()
                approx.rows() < 4 -> return null
            }
        }
        return null
    }

    private fun MatScope.canny(src: Mat, low: Double, high: Double): Mat = mat().also { Imgproc.Canny(src, it, low, high) }

    private fun MatScope.dilated(src: Mat, px: Int): Mat = mat().also { Imgproc.dilate(src, it, rect(px)) }

    private fun MatScope.rect(px: Int): Mat =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(px.toDouble(), px.toDouble())).track()

    private fun median(grey: Mat): Double {
        val bytes = ByteArray(grey.total().toInt()).also { grey.get(0, 0, it) }
        val histogram = IntArray(256)
        for (b in bytes) histogram[b.toInt() and 0xFF]++
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen * 2 >= bytes.size) return v.toDouble()
        }
        return 255.0
    }

    /** Fraction of points sampled along a quad's sides that land on an edge pixel. */
    private class EdgeSupport(edges: Mat, private val w: Int, private val h: Int) {
        private val pixels = ByteArray(w * h).also { edges.get(0, 0, it) }

        fun of(q: Quad): Float {
            val corners = q.corners
            var hits = 0
            var samples = 0
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % corners.size]
                for (s in 0 until SAMPLES_PER_SIDE) {
                    val t = 0.05f + 0.9f * s / (SAMPLES_PER_SIDE - 1)
                    val x = ((a.x + (b.x - a.x) * t) * w).toInt()
                    val y = ((a.y + (b.y - a.y) * t) * h).toInt()
                    if (x !in 0 until w || y !in 0 until h) continue
                    samples++
                    if (pixels[y * w + x].toInt() != 0) hits++
                }
            }
            return if (samples == 0) 0f else hits.toFloat() / samples
        }
    }

    private fun MatScope.resizeToLongEdge(src: Mat, longEdge: Int): Mat {
        val long = maxOf(src.cols(), src.rows())
        if (long <= longEdge) return src
        val s = longEdge.toDouble() / long
        return mat().also { Imgproc.resize(src, it, Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private fun MatScope.rotateUpright(src: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        return mat().also { Core.rotate(src, it, code) }
    }

    private companion object {
        const val TAG = "Detector"
        const val MAX_CANDIDATES = 10

        /** A quad filling the whole frame is the frame itself, not a page. */
        const val MAX_AREA_FRACTION = 0.995f
        const val AREA_BIAS = 0.5f

        /** At the 480 px analysis size: wider than a printed line, narrower than a page margin. */
        const val TEXT_ERASE_PX = 9
        const val SUPPORT_BAND_PX = 9
        const val WEAK_LOW = 10.0
        const val WEAK_HIGH = 30.0
        const val SAMPLES_PER_SIDE = 40
        val EPSILON_STEPS = doubleArrayOf(DetectionTuning.APPROX_EPSILON_FRACTION, 0.03, 0.04, 0.05, 0.06, 0.08)
    }
}

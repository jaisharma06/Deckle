package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.domain.imaging.EnhancementMode
import kotlin.math.max
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * NV-13: covers fingertips holding the page edge with the paper's own colour.
 *
 * A region is treated as a finger only if all of these hold, so page content is left alone:
 * - skin-coloured (YCrCb range) and clearly more saturated than the paper around it;
 * - touching the image border, and not reaching further in than [MAX_REACH] of the page;
 * - small: between [MIN_AREA] and [MAX_AREA] of the page, and covering under [MAX_BORDER_SHARE] of its side.
 * A warm-lit or beige page fails the size tests (it's all one region), and a photo of a hand printed on
 * the page fails the border test.
 */
internal object FingerRemover {
    /** Modes that make a clean document; Photo, Original and the styles keep the capture as it was. */
    val MODES: Set<EnhancementMode> = setOf(EnhancementMode.MAGIC, EnhancementMode.COLOUR, EnhancementMode.BLACK_AND_WHITE)

    private const val WORK_LONG_EDGE = 800.0
    private const val MIN_AREA = 0.001
    private const val MAX_AREA = 0.08
    private const val MAX_REACH = 0.3
    private const val MAX_BORDER_SHARE = 0.4
    /** How far a finger must out-saturate the paper (HSV S, 0…255). */
    private const val MIN_EXTRA_SATURATION = 25.0

    /** Returns [bgr] itself when nothing looks like a finger. */
    fun apply(scope: MatScope, bgr: Mat): Mat = with(scope) {
        val scale = WORK_LONG_EDGE / max(bgr.cols(), bgr.rows())
        val small = if (scale < 1) mat().also { Imgproc.resize(bgr, it, Size(bgr.cols() * scale, bgr.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) } else bgr
        val w = small.cols()
        val h = small.rows()

        val ycc = mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_BGR2YCrCb) }
        val skin = mat().also { Core.inRange(ycc, Scalar(40.0, 135.0, 85.0), Scalar(255.0, 180.0, 135.0), it) }
        val open = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)).track()
        Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_OPEN, open)

        val hsv = mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_BGR2HSV) }
        val sat = mat().also { Core.extractChannel(hsv, it, 1) }
        val notSkin = mat().also { Core.bitwise_not(skin, it) }
        val paperSat = Core.mean(sat, notSkin).`val`[0]

        val labels = mat()
        val stats = mat()
        val centroids = mat()
        val count = Imgproc.connectedComponentsWithStats(skin, labels, stats, centroids, 8, CvType.CV_32S)
        val fingers = Mat.zeros(h, w, CvType.CV_8U).track()
        val area = (w * h).toDouble()
        var found = false
        for (i in 1 until count) {
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val a = stats.get(i, Imgproc.CC_STAT_AREA)[0] / area
            if (a < MIN_AREA || a > MAX_AREA) continue
            val touchesLeft = x <= 1
            val touchesRight = x + bw >= w - 1
            val touchesTop = y <= 1
            val touchesBottom = y + bh >= h - 1
            val ok = (touchesLeft && bw <= w * MAX_REACH && bh <= h * MAX_BORDER_SHARE) ||
                (touchesRight && bw <= w * MAX_REACH && bh <= h * MAX_BORDER_SHARE) ||
                (touchesTop && bh <= h * MAX_REACH && bw <= w * MAX_BORDER_SHARE) ||
                (touchesBottom && bh <= h * MAX_REACH && bw <= w * MAX_BORDER_SHARE)
            if (!ok) continue
            val component = mat().also { Core.compare(labels, Scalar(i.toDouble()), it, Core.CMP_EQ) }
            if (Core.mean(sat, component).`val`[0] < paperSat + MIN_EXTRA_SATURATION) continue
            Core.bitwise_or(fingers, component, fingers)
            found = true
        }
        if (!found) return bgr

        // Grow the mask a little to take the finger's soft edge and shadow with it.
        val grow = (max(w, h) * 0.015).coerceAtLeast(3.0)
        val dilate = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(grow, grow)).track()
        Imgproc.dilate(fingers, fingers, dilate)

        // Paper colour: median-ish (mean of the non-finger border band) so the fill matches its surroundings.
        val band = Mat.zeros(h, w, CvType.CV_8U).track()
        Imgproc.rectangle(band, Rect(0, 0, w, h), Scalar(255.0), (max(w, h) * 0.08).toInt())
        val notFinger = mat().also { Core.bitwise_not(fingers, it) }
        val bandPaper = mat().also { Core.bitwise_and(band, notFinger, it) }
        val mean = MatOfDouble().track()
        val std = MatOfDouble().track()
        Core.meanStdDev(small, mean, std, bandPaper)
        val paper = Scalar(mean.get(0, 0)[0], mean.get(1, 0)[0], mean.get(2, 0)[0])

        // Full resolution: a feathered mask so there is no hard seam.
        val full = mat().also { Imgproc.resize(fingers, it, bgr.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
        val feather = (max(bgr.cols(), bgr.rows()) * 0.006).coerceAtLeast(3.0)
        Imgproc.GaussianBlur(full, full, Size(0.0, 0.0), feather)
        val alpha = mat().also { full.convertTo(it, CvType.CV_32F, 1.0 / 255) }
        val alpha3 = mat().also { Core.merge(listOf(alpha, alpha, alpha), it) }
        val src = mat().also { bgr.convertTo(it, CvType.CV_32FC3) }
        val fill = Mat(bgr.size(), CvType.CV_32FC3, paper).track()
        val inv = mat().also { Core.subtract(Mat(alpha3.size(), CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0)).track(), alpha3, it) }
        val a = mat().also { Core.multiply(src, inv, it) }
        val b = mat().also { Core.multiply(fill, alpha3, it) }
        val sum = mat().also { Core.add(a, b, it) }
        mat().also { sum.convertTo(it, CvType.CV_8UC3) }
    }
}

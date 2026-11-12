package com.avyaan.deckle.data.imaging.opencv

import kotlin.math.pow
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/*
 * Building blocks shared by the enhancement filters. Each returns a new Mat tracked by the scope and
 * never modifies its input; 8-bit arithmetic saturates (clamps to 0…255) rather than wrapping.
 */

/** Linear levels: maps [black]…[white] to 0…255 on every channel. */
internal fun MatScope.levels(src: Mat, black: Double, white: Double): Mat {
    val alpha = 255.0 / (white - black).coerceAtLeast(1.0)
    return mat().also { src.convertTo(it, -1, alpha, -black * alpha) }
}

/** Multiplies HSV saturation by [factor] (BGR in, BGR out). */
internal fun MatScope.scaleSaturation(bgr: Mat, factor: Double): Mat {
    val hsv = mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2HSV) }
    val channels = ArrayList<Mat>(3)
    Core.split(hsv, channels)
    channels.forEach { it.track() }
    channels[1] = mat().also { channels[1].convertTo(it, -1, factor, 0.0) }
    val merged = mat().also { Core.merge(channels, it) }
    return mat().also { Imgproc.cvtColor(merged, it, Imgproc.COLOR_HSV2BGR) }
}

/** CLAHE on the L channel of Lab: local contrast without shifting hue. */
internal fun MatScope.claheLightness(bgr: Mat, clip: Double, tiles: Double = 8.0): Mat {
    val lab = mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2Lab) }
    val channels = ArrayList<Mat>(3)
    Core.split(lab, channels)
    channels.forEach { it.track() }
    val clahe = Imgproc.createCLAHE(clip, Size(tiles, tiles))
    channels[0] = mat().also { clahe.apply(channels[0], it) }
    val merged = mat().also { Core.merge(channels, it) }
    return mat().also { Imgproc.cvtColor(merged, it, Imgproc.COLOR_Lab2BGR) }
}

/** Gamma curve through a lookup table; gamma < 1 brightens mid-tones while keeping black and white fixed. */
internal fun MatScope.gamma(src: Mat, gamma: Double): Mat {
    val table = ByteArray(256) { i -> (255.0 * (i / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255).toByte() }
    val lut = Mat(1, 256, CvType.CV_8U).track().also { it.put(0, 0, table) }
    return mat().also { Core.LUT(src, lut, it) }
}

/** Unsharp mask: src + amount·(src − blur(src)). */
internal fun MatScope.unsharp(src: Mat, sigma: Double, amount: Double): Mat {
    val blur = mat().also { Imgproc.GaussianBlur(src, it, Size(0.0, 0.0), sigma) }
    return mat().also { Core.addWeighted(src, 1.0 + amount, blur, -amount, 0.0, it) }
}

/**
 * Grey-world white balance: scales each channel so their means agree. Gains are capped so a page that
 * really is one colour (a blue form, a yellow sticky note) is corrected only slightly, not neutralised.
 */
internal fun MatScope.greyWorld(bgr: Mat, maxGain: Double = 1.2): Mat {
    val means = Core.mean(bgr).`val`
    val average = (means[0] + means[1] + means[2]) / 3.0
    if (average < 1.0) return bgr
    fun gain(mean: Double) = (average / mean.coerceAtLeast(1.0)).coerceIn(1.0 / maxGain, maxGain)
    return mat().also { Core.multiply(bgr, Scalar(gain(means[0]), gain(means[1]), gain(means[2])), it) }
}

/**
 * Auto-levels on luminance: the [clip] darkest and brightest fraction of pixels become pure black and
 * white. The same mapping is applied to all channels, so hues are preserved.
 */
internal fun MatScope.autoLevels(bgr: Mat, clip: Double): Mat {
    val grey = mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2GRAY) }
    val hist = mat()
    Imgproc.calcHist(listOf(grey), MatOfInt(0).track(), Mat().track(), hist, MatOfInt(256).track(), MatOfFloat(0f, 256f).track())
    val total = grey.total().toDouble()
    var cumulative = 0.0
    var black = 0
    var white = 255
    var blackFound = false
    for (i in 0 until 256) {
        cumulative += hist.get(i, 0)[0]
        if (!blackFound && cumulative >= total * clip) { black = i; blackFound = true }
        if (cumulative >= total * (1 - clip)) { white = i; break }
    }
    // A flat image (white − black tiny) would be blown up into noise: leave it alone.
    if (white - black < MIN_LEVELS_RANGE) return bgr
    return levels(bgr, black.toDouble(), white.toDouble())
}

/**
 * Evens out lighting by dividing lightness by a very blurred copy of itself (the illumination estimate).
 * Gentler than [ShadowFlattener]: it removes only the slow fall-off of a shadow or lamp, so photos and
 * colour blocks on the page survive. The blur is computed on a [workEdge]-pixel copy and scaled back up,
 * because a page-scale Gaussian at full resolution would need a kernel thousands of pixels wide.
 */
internal fun MatScope.evenLighting(bgr: Mat, blurFraction: Double, target: Double, workEdge: Int = 256): Mat {
    val lab = mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2Lab) }
    val channels = ArrayList<Mat>(3)
    Core.split(lab, channels)
    channels.forEach { it.track() }
    val lightness = channels[0]

    val scale = workEdge.toDouble() / maxOf(lightness.cols(), lightness.rows())
    val small = mat().also { Imgproc.resize(lightness, it, Size(lightness.cols() * scale, lightness.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) }
    val smallF = mat().also { small.convertTo(it, CvType.CV_32F) }
    val blurred = mat().also { Imgproc.GaussianBlur(smallF, it, Size(0.0, 0.0), workEdge * blurFraction) }
    val illumination = mat().also { Imgproc.resize(blurred, it, lightness.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
    Core.max(illumination, Scalar(1.0), illumination)

    val lightnessF = mat().also { lightness.convertTo(it, CvType.CV_32F) }
    val evened = mat().also { Core.divide(lightnessF, illumination, it, target) }
    channels[0] = mat().also { evened.convertTo(it, CvType.CV_8U) }   // saturating
    val merged = mat().also { Core.merge(channels, it) }
    return mat().also { Imgproc.cvtColor(merged, it, Imgproc.COLOR_Lab2BGR) }
}

private const val MIN_LEVELS_RANGE = 32

package com.avyaan.deckle.feature.capture.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.imaging.LumaFrame
import com.avyaan.deckle.domain.imaging.PageDetector

/** One live-lane result: a quad in the upright frame (or null) and the frame's upright size. */
data class DetectionResult(val quad: Quad?, val uprightWidth: Int, val uprightHeight: Int)

/**
 * Live lane (spec 03 §2.1): wraps the Y plane without copying and hands it to the detector.
 * Runs on the analysis executor; `STRATEGY_KEEP_ONLY_LATEST` drops frames while we're busy.
 */
class DetectionAnalyzer(
    private val detector: PageDetector,
    private val onResult: (DetectionResult) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use {
            val y = it.planes[0]
            val rotation = it.imageInfo.rotationDegrees
            val frame = LumaFrame(y.buffer, it.width, it.height, y.rowStride, rotation)
            val (w, h) = PreviewMapping.uprightSize(it.width, it.height, rotation)
            onResult(DetectionResult(detector.detect(frame), w, h))
        }
    }
}

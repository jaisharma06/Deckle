package com.avyaan.deckle.data.imaging.opencv

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
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import javax.inject.Inject
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Rotation (FR-24), then homography dewarp (spec 03 §7.1). Writes the base image at JPEG q95. */
public class OpenCvPerspectiveCorrector @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : PerspectiveCorrector {

    override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String): Outcome<ImageSize> =
        guarded(dispatchers) {
            matScope {
                val src = Imgcodecs.imread(sourcePath, Imgcodecs.IMREAD_COLOR).track()
                if (src.empty()) return@matScope Outcome.Failure(DeckleError.ImageDecodeFailed)
                val upright = rotate(src, rotation)
                // The quad is stored against the unrotated source; turn it with the image.
                val q = CornerOrdering.order(QuadRotation.rotate(quad, rotation).corners)
                val size = ImageSize(upright.cols(), upright.rows())

                val result = if (q == Quad.FULL) upright else warp(upright, q, size)
                writeJpeg(result, out, BASE_QUALITY)
                Outcome.Success(ImageSize(result.cols(), result.rows()))
            }
        }

    private fun MatScope.warp(src: Mat, q: Quad, size: ImageSize): Mat {
        val target = QuadGeometry.outputSize(q, size)
        fun px(p: com.avyaan.deckle.domain.geometry.PointN) = Point(p.x * (size.width - 1).toDouble(), p.y * (size.height - 1).toDouble())
        val from = MatOfPoint2f(px(q.tl), px(q.tr), px(q.br), px(q.bl)).track()
        val w = (target.width - 1).toDouble()
        val h = (target.height - 1).toDouble()
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)).track()
        val m = Imgproc.getPerspectiveTransform(from, to).track()
        return mat().also {
            Imgproc.warpPerspective(src, it, m, Size(target.width.toDouble(), target.height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        }
    }

    private fun MatScope.rotate(src: Mat, rotation: Rotation): Mat {
        val code = when (rotation) {
            Rotation.R0 -> return src
            Rotation.R90 -> Core.ROTATE_90_CLOCKWISE
            Rotation.R180 -> Core.ROTATE_180
            Rotation.R270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        }
        return mat().also { Core.rotate(src, it, code) }
    }

    private companion object {
        const val BASE_QUALITY = 95
    }
}

package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.imaging.SharpnessMeter
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * NV-10. For an edge blurred by σ, the strongest gradient scales with 1/σ and the strongest second
 * derivative with 1/σ², so their ratio scales with 1/σ whatever the ink contrast or how much text the page
 * holds. Both are taken as high percentiles (not maxima, which one speck of dust decides) on a copy scaled
 * to a fixed size, so the score doesn't depend on camera resolution.
 */
public class OpenCvSharpnessMeter @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : SharpnessMeter {

    override suspend fun measure(imagePath: String): Float? = withContext(dispatchers.imaging) { score(imagePath) }

    internal fun score(imagePath: String): Float? = matScope {
        val grey = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE).track()
        if (grey.empty()) return@matScope null
        val scale = WORK_LONG_EDGE / max(grey.cols(), grey.rows()).toDouble()
        val work = mat().also { Imgproc.resize(grey, it, Size(grey.cols() * scale, grey.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) }
        val f = mat().also { work.convertTo(it, CvType.CV_32F) }

        val gx = mat().also { Imgproc.Sobel(f, it, CvType.CV_32F, 1, 0, 3) }
        val gy = mat().also { Imgproc.Sobel(f, it, CvType.CV_32F, 0, 1, 3) }
        val grad = mat().also { Core.magnitude(gx, gy, it) }
        val lap = mat().also { Imgproc.Laplacian(f, it, CvType.CV_32F, 3) }
        val lapAbs = mat().also { Core.absdiff(lap, org.opencv.core.Scalar(0.0), it) }

        val g = percentile(grad, PERCENTILE)
        // Blank or nearly blank pages have no edges to judge by: "can't tell", never "blurry".
        if (g < MIN_EDGE_STRENGTH) return@matScope null
        (percentile(lapAbs, PERCENTILE) / g).toFloat()
    }

    private fun percentile(m: Mat, p: Double): Double {
        val values = FloatArray((m.total() * m.channels()).toInt())
        m.get(0, 0, values)
        values.sort()
        return values[((values.size - 1) * p).toInt()].toDouble()
    }

    internal companion object {
        const val WORK_LONG_EDGE = 1200.0
        const val PERCENTILE = 0.995
        /** Sobel units (×4 of a 0…255 step): below this the page is practically blank. */
        const val MIN_EDGE_STRENGTH = 60.0
    }
}

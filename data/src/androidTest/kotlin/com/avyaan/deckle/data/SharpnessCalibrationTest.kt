package com.avyaan.deckle.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avyaan.deckle.data.imaging.engine.OpenCvStatus
import com.avyaan.deckle.data.imaging.opencv.OpenCvSharpnessMeter
import com.avyaan.deckle.data.imaging.opencv.matScope
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.imaging.BlurPolicy
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * NV-10 calibration on generated pages (no personal documents): printed text at a typical A4 scale,
 * blurred by known amounts plus sensor-like noise. The threshold must sit between "slightly soft but easy
 * to read" and "visibly blurry". Scores are logged under tag SharpnessCalibration.
 */
@RunWith(AndroidJUnit4::class)
class SharpnessCalibrationTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sharpness").apply { mkdirs() }
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val imaging = Dispatchers.Unconfined
        override val preview = Dispatchers.Unconfined
    }
    private val meter = OpenCvSharpnessMeter(dispatchers)

    @Before fun setUp() {
        OpenCvStatus.init()
        assertThat(OpenCvStatus.isAvailable).isTrue()
    }

    /** An A4-ish page of body text (or [lines] lines of it) at [width] px. */
    private fun page(name: String, width: Int = 1240, lines: Int = 40, textSize: Float = 24f, grey: Int = 20): File {
        val height = (width * 1.414).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(246, 244, 238))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(grey, grey, grey); this.textSize = textSize * width / 1240f; typeface = Typeface.SERIF
        }
        val words = "The quick brown fox jumps over the lazy dog. Invoice total 4,250.00 due 14 Nov 2026; ref A7-3391. "
        val lineHeight = paint.textSize * 1.5f
        for (i in 0 until lines) c.drawText(words.repeat(2), width * 0.08f, height * 0.08f + i * lineHeight, paint)
        val f = File(dir, "$name.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    /** Gaussian blur of [sigma] px (at 1240 px width) or a horizontal shake of [motion] px, plus noise. */
    private fun degrade(src: File, name: String, sigma: Double = 0.0, motion: Int = 0): File = matScope {
        val img = Imgcodecs.imread(src.path).track()
        var out: Mat = img
        if (sigma > 0) out = mat().also { Imgproc.GaussianBlur(out, it, Size(0.0, 0.0), sigma) }
        if (motion > 1) {
            val k = Mat.zeros(1, motion, CvType.CV_32F).track()
            for (i in 0 until motion) k.put(0, i, 1.0 / motion)
            out = mat().also { Imgproc.filter2D(out, it, -1, k) }
        }
        val noise = Mat(out.size(), CvType.CV_16SC3).track()
        Core.randn(noise, 0.0, 3.0)
        val noisy = mat().also { out.convertTo(it, CvType.CV_16SC3) }
        Core.add(noisy, noise, noisy)
        val back = mat().also { noisy.convertTo(it, CvType.CV_8UC3) }
        File(dir, "$name.jpg").also { Imgcodecs.imwrite(it.path, back) }
    }

    private fun score(f: File): Float? = runBlocking { meter.measure(f.path) }.also { Log.i(TAG, "${f.name}: $it") }

    @Test fun sharpAndSlightlySoftPagesPass_blurryAndShakenPagesAreFlagged() {
        val clean = page("clean")
        val sharp = score(degrade(clean, "sigma0.6", sigma = 0.6))!!
        val soft = score(degrade(clean, "sigma1.0", sigma = 1.0))!!
        val blurry = score(degrade(clean, "sigma2.0", sigma = 2.0))!!
        val veryBlurry = score(degrade(clean, "sigma3.5", sigma = 3.5))!!
        val shaken = score(degrade(clean, "motion9", motion = 9))!!
        val all = "sharp=$sharp soft=$soft blurry=$blurry very=$veryBlurry shaken=$shaken threshold=${BlurPolicy.BLURRY_BELOW}"
        Log.i(TAG, all)

        assertThat(sharp).isGreaterThan(soft)
        assertThat(soft).isGreaterThan(blurry)
        assertThat(blurry).isGreaterThan(veryBlurry)
        assertWithMessage(all).that(BlurPolicy.isBlurry(sharp)).isFalse()
        assertWithMessage(all).that(BlurPolicy.isBlurry(soft)).isFalse()
        assertWithMessage(all).that(BlurPolicy.isBlurry(blurry)).isTrue()
        assertWithMessage(all).that(BlurPolicy.isBlurry(veryBlurry)).isTrue()
        assertWithMessage(all).that(BlurPolicy.isBlurry(shaken)).isTrue()
    }

    @Test fun scoreDoesNotDependOnResolutionOrHowMuchTextThereIs() {
        val bigSharp = score(degrade(page("big", width = 3000), "big-sharp", sigma = 1.4))!!     // same blur as 0.6 at 1240
        val sparse = score(degrade(page("sparse", lines = 3), "sparse-sharp", sigma = 0.6))!!
        val faint = score(degrade(page("faint", grey = 150), "faint-sharp", sigma = 0.6))!!     // pencil-light ink
        assertThat(BlurPolicy.isBlurry(bigSharp)).isFalse()
        assertThat(BlurPolicy.isBlurry(sparse)).isFalse()
        assertThat(BlurPolicy.isBlurry(faint)).isFalse()
    }

    @Test fun aBlankPageIsNeverCalledBlurry() {
        val blank = score(degrade(page("blank", lines = 0), "blank", sigma = 3.0))
        assertThat(BlurPolicy.isBlurry(blank)).isFalse()
    }

    private companion object { const val TAG = "SharpnessCalibration" }
}

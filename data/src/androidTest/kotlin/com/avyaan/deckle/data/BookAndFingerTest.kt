package com.avyaan.deckle.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avyaan.deckle.data.imaging.engine.OpenCvStatus
import com.avyaan.deckle.data.imaging.opencv.FingerRemover
import com.avyaan.deckle.data.imaging.opencv.OpenCvBookSplitter
import com.avyaan.deckle.data.imaging.opencv.matScope
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs

/** NV-12 and NV-13 on generated images (no personal documents). Logs under tag Phase2Imaging. */
@RunWith(AndroidJUnit4::class)
class BookAndFingerTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "phase2").apply { mkdirs() }
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val imaging = Dispatchers.Unconfined
        override val preview = Dispatchers.Unconfined
    }

    @Before fun setUp() {
        OpenCvStatus.init()
        assertThat(OpenCvStatus.isAvailable).isTrue()
    }

    // ---- Book ----

    private val w = 2400
    private val h = 1600
    private val gutter = 1128            // 47 % of the width: not exactly the middle

    /** How far the page edge dips toward the gutter at column x. */
    private fun dip(x: Float) = 90f * exp(-abs(gutter - x) / 160f)
    private fun top(x: Float) = 140f + dip(x)
    private fun bottom(x: Float) = 1460f - dip(x)

    private fun spread(): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(70, 60, 55))                               // a wooden table
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 236, 228) }
        val outline = Path().apply {
            moveTo(60f, top(60f))
            for (x in 60..w - 60 step 8) lineTo(x.toFloat(), top(x.toFloat()))
            for (x in w - 60 downTo 60 step 8) lineTo(x.toFloat(), bottom(x.toFloat()))
            close()
        }
        c.drawPath(outline, paper)
        // Gutter shadow.
        val shade = Paint().apply {
            shader = LinearGradient(gutter - 120f, 0f, gutter + 120f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(gutter - 120f, 0f, gutter + 120f, h.toFloat(), shade)
        // Text lines that bend with the page.
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); strokeWidth = 6f }
        for (line in 1..18) {
            val t = line / 19f
            for (x in 120 until w - 120 step 4) {
                if (abs(x - gutter) < 70) continue
                val y = top(x.toFloat()) + t * (bottom(x.toFloat()) - top(x.toFloat()))
                c.drawPoint(x.toFloat(), y, ink)
            }
        }
        return File(dir, "spread.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    /** Row of the first paper pixel from the top, per column (paper is bright, the table dark). */
    private fun topEdge(file: File): List<Int> {
        val b = BitmapFactory.decodeFile(file.path)
        return (b.width / 10 until b.width * 9 / 10 step 10).map { x ->
            (0 until b.height).first { y -> Color.red(b.getPixel(x, y)) > 160 }
        }
    }

    @Test fun splitsAtTheGutterAndStraightensTheCurl() = runBlocking {
        val splitter = OpenCvBookSplitter(dispatchers)
        val spineCol = matScope {
            val img = Imgcodecs.imread(spread().path).track()
            with(splitter) { findSpine(img) }
        }
        Log.i(TAG, "spine $spineCol (drawn at $gutter)")
        assertThat(abs(spineCol - gutter)).isLessThan(w / 50)

        val left = File(dir, "left.jpg")
        val right = File(dir, "right.jpg")
        assertThat(splitter.split(spread().path, left.path, right.path)).isEqualTo(Outcome.Success(Unit))

        val inputDip = dip(gutter.toFloat())                             // 90 px of bend at the gutter
        listOf(left, right).forEach { half ->
            val edge = topEdge(half)
            val bend = edge.max() - edge.min()
            Log.i(TAG, "${half.name}: top edge bend $bend px (was $inputDip)")
            assertThat(bend.toFloat()).isLessThan(inputDip * 0.25f)     // at least 75 % of the curl removed
        }
    }

    @Test fun aFlatPageWithNoVisibleEdgesIsOnlySplit() = runBlocking {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(240, 236, 228)) }
        val flat = File(dir, "flat.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        val left = File(dir, "flat-left.jpg")
        val right = File(dir, "flat-right.jpg")
        assertThat(OpenCvBookSplitter(dispatchers).split(flat.path, left.path, right.path)).isEqualTo(Outcome.Success(Unit))
        val l = BitmapFactory.decodeFile(left.path)
        val r = BitmapFactory.decodeFile(right.path)
        assertThat(l.width + r.width).isEqualTo(w)
        assertThat(l.height).isEqualTo(h)
    }

    // ---- Fingers ----

    private val skin = Color.rgb(205, 145, 115)

    private fun page(fingerAt: (Canvas) -> Unit, paper: Int = Color.rgb(238, 236, 232)): File {
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paper)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 25); textSize = 26f }
        for (i in 0 until 45) c.drawText("Lorem ipsum dolor sit amet, invoice 2026-11-14 total 1,240.00", 110f, 120f + i * 36f, ink)
        fingerAt(c)
        return File(dir, "fingers-${System.nanoTime()}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
    }

    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin }

    /** Mean colour of a box, after finger removal. */
    private fun afterRemoval(file: File, box: android.graphics.Rect): Int = matScope {
        val img = Imgcodecs.imread(file.path).track()
        val out = FingerRemover.apply(this, img)
        val roi = out.submat(org.opencv.core.Rect(box.left, box.top, box.width(), box.height())).track()
        val m = Core.mean(roi).`val`
        Color.rgb(m[2].toInt(), m[1].toInt(), m[0].toInt())
    }

    private fun saturation(c: Int): Float = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[1]

    @Test fun aFingertipAtTheEdgeIsCovered() {
        val f = page({ it.drawOval(-60f, 700f, 150f, 860f, fingerPaint) })
        val after = afterRemoval(f, android.graphics.Rect(5, 740, 90, 820))
        Log.i(TAG, "edge finger after: ${Integer.toHexString(after)}")
        assertThat(saturation(after)).isLessThan(0.08f)                  // paper-coloured now, not skin
    }

    @Test fun skinColouredContentInsideThePageIsKept() {
        val f = page({ it.drawOval(500f, 800f, 710f, 960f, fingerPaint) })
        val after = afterRemoval(f, android.graphics.Rect(560, 850, 650, 910))
        assertThat(saturation(after)).isGreaterThan(0.2f)
    }

    @Test fun aBeigePageIsLeftAlone() {
        val beige = Color.rgb(236, 222, 196)
        val f = page({}, paper = beige)
        val after = afterRemoval(f, android.graphics.Rect(10, 10, 100, 100))
        assertThat(abs(Color.red(after) - Color.red(beige))).isLessThan(8)
        assertThat(abs(Color.blue(after) - Color.blue(beige))).isLessThan(8)
    }

    private companion object { const val TAG = "Phase2Imaging" }
}

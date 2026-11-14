package com.avyaan.deckle.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.avyaan.deckle.data.export.MarkupCompositor
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.Page
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** NV-40…43 export compositing, on real pixels (native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkupCompositorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val compositor = MarkupCompositor(BitmapIO(context))
    private val work = File(context.cacheDir, "markup-test").apply { deleteRecursively() }

    private fun whitePage(): File = File(context.cacheDir, "white.jpg").also { f ->
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    private val lines = listOf(
        OcrLine("Name: Jai", RectN(0.1f, 0.1f, 0.5f, 0.13f), 1f),
        OcrLine("Aadhaar 1234 5678 9012", RectN(0.1f, 0.5f, 0.8f, 0.53f), 1f),
    )

    private fun page(path: File, markup: PageMarkup?) = Page(
        "p", "d", 0, null, path.path, path.path, path.path, Quad.FULL, Rotation.R0, EnhancementMode.COLOUR, Adjustments.NEUTRAL,
        400, 600, OcrState(OcrStatus.DONE, lines, 1), markup = markup,
    )

    private fun lum(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3

    @Test fun `a page without marks or watermark is exported untouched`() {
        val src = whitePage()
        val prepared = compositor.prepare(page(src, null), watermark = "  ", maxLongEdge = null, workDir = work)
        assertThat(prepared.imagePath).isEqualTo(src.path)
        assertThat(prepared.temporary).isFalse()
        assertThat(prepared.lines).isEqualTo(lines)
    }

    @Test fun `redaction blacks out the pixels and removes the text under it, leaving the stored page alone`() {
        val src = whitePage()
        val before = src.readBytes()
        val markup = PageMarkup(listOf(MarkupItem.Redaction("r", 0.05f, 0.48f, 0.9f, 0.56f)))
        val prepared = compositor.prepare(page(src, markup), watermark = null, maxLongEdge = null, workDir = work)

        assertThat(prepared.temporary).isTrue()
        assertThat(prepared.lines.map { it.text }).containsExactly("Name: Jai")
        val out = BitmapFactory.decodeFile(prepared.imagePath)
        assertThat(lum(out.getPixel(200, 312))).isLessThan(20)          // inside the box: black
        assertThat(lum(out.getPixel(200, 100))).isGreaterThan(235)      // elsewhere: still white
        assertThat(src.readBytes()).isEqualTo(before)                   // the stored page never changes
    }

    @Test fun `ink and a signature are burned in`() {
        val src = whitePage()
        val ink = MarkupItem.Ink("i", listOf(0.1f, 0.2f, 0.9f, 0.2f), MarkupItem.INK_RED, 0.03f)
        val sig = MarkupItem.Signature("s", SignatureInk(listOf(listOf(0f, 0.1f, 1f, 0.1f)), 0.2f), cx = 0.5f, cy = 0.8f, width = 0.6f)
        val prepared = compositor.prepare(page(src, PageMarkup(listOf(ink, sig))), null, null, work)
        val out = BitmapFactory.decodeFile(prepared.imagePath)
        val red = out.getPixel(200, 120)
        assertThat(Color.red(red)).isGreaterThan(150)
        assertThat(Color.green(red)).isLessThan(100)
        // Signature line runs through its centre row (cy) across most of its width.
        assertThat(lum(out.getPixel(200, 480))).isLessThan(150)
        assertThat(prepared.lines).hasSize(2)                            // no redaction: all text stays
    }

    @Test fun `a watermark tints the page without hiding it`() {
        val src = whitePage()
        val prepared = compositor.prepare(page(src, null), watermark = "Copy for bank KYC only", maxLongEdge = 200, workDir = work)
        val out = BitmapFactory.decodeFile(prepared.imagePath)
        assertThat(maxOf(out.width, out.height)).isAtMost(200)
        val pixels = IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
        val tinted = pixels.count { lum(it) < 235 }
        assertThat(tinted).isGreaterThan(pixels.size / 100)              // the text is there…
        assertThat(pixels.count { lum(it) < 120 }).isEqualTo(0)          // …and stays light
    }
}

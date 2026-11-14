package com.avyaan.deckle.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.data.markup.MarkupPainter
import com.avyaan.deckle.domain.markup.Redaction
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.page.Page
import java.io.File
import javax.inject.Inject

/** The image and text-layer lines one exported page should use. [temporary] files are deleted after the export. */
public data class PreparedPage(val imagePath: String, val lines: List<OcrLine>, val temporary: Boolean)

/**
 * NV-40…43 at export time: burns the page's markup and the watermark into a temporary copy of the page,
 * never into the stored page. Redacted areas are painted solid black in the pixels themselves (not an
 * overlay a PDF viewer could remove), and OCR lines under them are left out of the text layer.
 */
public class MarkupCompositor @Inject constructor(private val bitmaps: BitmapIO) {
    private val painter = MarkupPainter()

    /** [maxLongEdge] null keeps full resolution. Throws if the page can't be decoded or written. */
    public fun prepare(page: Page, watermark: String?, maxLongEdge: Int?, workDir: File): PreparedPage {
        val markup = page.markup
        val lines = Redaction.visibleLines(page.ocr.lines.orEmpty(), markup)
        val label = watermark?.trim().orEmpty()
        if ((markup == null || markup.isEmpty) && label.isEmpty()) return PreparedPage(page.processedPath, lines, temporary = false)

        val decoded = bitmaps.decodeFile(page.processedPath, maxLongEdge) ?: error("Couldn't decode ${page.id}")
        val bitmap = if (decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        try {
            val canvas = Canvas(bitmap)
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            painter.draw(canvas, markup, w, h)
            painter.drawWatermark(canvas, label, w, h)
            workDir.mkdirs()
            val out = File(workDir, "${page.id}.jpg")
            bitmaps.writeJpeg(bitmap, out.path, QUALITY)
            return PreparedPage(out.path, lines, temporary = true)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val QUALITY = 95
    }
}

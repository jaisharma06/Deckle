package com.avyaan.deckle.data.export

import android.graphics.Bitmap
import com.avyaan.deckle.data.files.AtomicFiles
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.export.PdfComposer
import com.avyaan.deckle.domain.export.PdfLayout
import com.avyaan.deckle.domain.export.PdfRequest
import com.avyaan.deckle.domain.export.PlacedLine
import com.avyaan.deckle.domain.export.QualityPreset
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Searchable PDF (spec 04 §3): each page is the processed JPEG with an invisible OCR text layer placed by
 * [PdfLayout]. One page in memory at a time; optional AES-256 protection (FR-55).
 */
public class PdfBoxComposer @Inject constructor(
    private val bitmaps: BitmapIO,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : PdfComposer {

    override suspend fun compose(request: PdfRequest, out: String, onProgress: (done: Int, total: Int) -> Unit): Outcome<Long> =
        withContext(dispatchers.io) {
            try {
                PDDocument().use { doc ->
                    // Helvetica is a standard-14 font: Latin-only (D-1) and nothing to embed.
                    val font: PDFont = PDType1Font.HELVETICA
                    val encodable = EncodableCache(font)
                    request.pages.forEachIndexed { i, input ->
                        coroutineContext.ensureActive()
                        val layout = PdfLayout.page(input.widthPx, input.heightPx, request.pageSize)
                        val page = PDPage(PDRectangle(layout.pageWidth, layout.pageHeight)).also(doc::addPage)
                        val image = imageFor(doc, input.imagePath, request.quality)
                        val lines = input.lines.mapNotNull { line -> encodable.subset(line.text)?.let { line.copy(text = it) } }
                        val placed = PdfLayout.lines(layout, lines) { text -> runCatching { font.getStringWidth(text) }.getOrNull() }
                        PDPageContentStream(doc, page).use { cs ->
                            cs.drawImage(image, layout.imageX, layout.imageY, layout.imageWidth, layout.imageHeight)
                            placed.forEach { writeInvisibleLine(cs, font, it) }
                        }
                        onProgress(i + 1, request.pages.size)
                    }
                    request.password?.let { doc.protect(protection(it)) }
                    val file = File(out)
                    AtomicFiles.write(file) { stream -> doc.save(stream) }
                    Outcome.Success(file.length())
                }
            } catch (e: CancellationException) {
                File(out).delete()
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "PDF export failed", e)
                File(out).delete()
                Outcome.Failure(DeckleError.ExportFailed(e.javaClass.simpleName))
            }
        }

    /** ORIGINAL embeds the processed JPEG untouched (DCT passthrough); others re-encode one page at a time. */
    private fun imageFor(doc: PDDocument, path: String, quality: QualityPreset): PDImageXObject {
        val maxEdge = quality.maxLongEdge
        val jpegQuality = quality.jpegQuality
        if (maxEdge == null || jpegQuality == null) return File(path).inputStream().use { JPEGFactory.createFromStream(doc, it) }
        val bitmap = bitmaps.decodeFile(path, maxEdge) ?: error("Couldn't decode $path")
        val bytes = try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        return JPEGFactory.createFromByteArray(doc, bytes)
    }

    /** FR-68: invisible text (rendering mode NEITHER). One bad line is dropped, never the page. */
    private fun writeInvisibleLine(cs: PDPageContentStream, font: PDFont, line: PlacedLine) {
        try {
            cs.beginText()
            cs.setFont(font, line.fontSize)
            cs.setRenderingMode(RenderingMode.NEITHER)
            cs.newLineAtOffset(line.x, line.baselineY)
            cs.showText(line.text)
            cs.endText()
        } catch (e: IllegalArgumentException) {
            runCatching { cs.endText() }
        }
    }

    private fun protection(password: CharArray): StandardProtectionPolicy {
        val owner = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        return StandardProtectionPolicy(owner, String(password), AccessPermission()).apply { encryptionKeyLength = 256 }
    }

    /**
     * Replaces characters the font can't encode (e.g. "₹") with spaces so the rest of the line stays
     * searchable. Per-character results are cached for the export. Null if nothing useful is left.
     */
    private class EncodableCache(private val font: PDFont) {
        private val known = HashMap<Int, Boolean>()

        fun subset(text: String): String? {
            val sb = StringBuilder(text.length)
            text.codePoints().forEach { cp ->
                val ok = known.getOrPut(cp) { runCatching { font.encode(String(Character.toChars(cp))) }.isSuccess }
                if (ok) sb.appendCodePoint(cp) else sb.append(' ')
            }
            return sb.toString().takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val TAG = "PdfBoxComposer"
    }
}

package com.avyaan.deckle.domain.export

import com.avyaan.deckle.domain.ocr.OcrLine

/** Page and image placement on one PDF page, in PDF points (origin bottom-left). */
public data class PdfPageLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    val imageX: Float,
    val imageY: Float,
    val imageWidth: Float,
    val imageHeight: Float,
)

/** Where and how large to write one invisible OCR line. */
public data class PlacedLine(val text: String, val x: Float, val baselineY: Float, val fontSize: Float)

/**
 * The arithmetic of the searchable PDF (FR-51, FR-69), kept free of PDFBox so it is unit-tested.
 */
public object PdfLayout {
    public const val A4_W: Float = 595f
    public const val A4_H: Float = 842f
    public const val LETTER_W: Float = 612f
    public const val LETTER_H: Float = 792f
    /** AUTO pages get the A4 long edge, so they have a sensible physical size. */
    public const val AUTO_LONG_EDGE: Float = 842f
    /** FR-69: lines whose computed size is below 1 pt are skipped. */
    public const val MIN_FONT_SIZE: Float = 1f

    public fun page(widthPx: Int, heightPx: Int, policy: PageSizePolicy): PdfPageLayout {
        require(widthPx > 0 && heightPx > 0) { "Image must have a size" }
        val landscape = widthPx > heightPx
        return when (policy) {
            PageSizePolicy.AUTO -> {
                val scale = AUTO_LONG_EDGE / maxOf(widthPx, heightPx)
                val w = widthPx * scale
                val h = heightPx * scale
                PdfPageLayout(w, h, 0f, 0f, w, h)
            }
            PageSizePolicy.A4 -> letterbox(widthPx, heightPx, if (landscape) A4_H else A4_W, if (landscape) A4_W else A4_H)
            PageSizePolicy.LETTER -> letterbox(widthPx, heightPx, if (landscape) LETTER_H else LETTER_W, if (landscape) LETTER_W else LETTER_H)
        }
    }

    private fun letterbox(iw: Int, ih: Int, pw: Float, ph: Float): PdfPageLayout {
        val scale = minOf(pw / iw, ph / ih)
        val w = iw * scale
        val h = ih * scale
        return PdfPageLayout(pw, ph, (pw - w) / 2f, (ph - h) / 2f, w, h)
    }

    /**
     * Places each OCR line over the image. Two conversions (FR-69):
     * 1. y flips: PDF origin is bottom-left, image origin is top-left.
     * 2. font size starts at the box height, then is corrected so the string spans the box width.
     *
     * [widthPer1000] returns the font's advance width of a string in 1/1000 em units.
     */
    public fun lines(layout: PdfPageLayout, lines: List<OcrLine>, widthPer1000: (String) -> Float?): List<PlacedLine> =
        lines.mapNotNull { line -> place(layout, line, widthPer1000) }

    private fun place(layout: PdfPageLayout, line: OcrLine, widthPer1000: (String) -> Float?): PlacedLine? {
        if (line.text.isBlank()) return null
        val boxW = line.box.width * layout.imageWidth
        val boxH = line.box.height * layout.imageHeight
        if (boxW <= 0f || boxH <= 0f) return null

        val x = layout.imageX + line.box.left * layout.imageWidth
        val baselineY = layout.imageY + layout.imageHeight - line.box.bottom * layout.imageHeight

        val units = widthPer1000(line.text) ?: return null
        val textWidthAtBoxHeight = units / 1000f * boxH
        val size = if (textWidthAtBoxHeight > 0f) boxH * (boxW / textWidthAtBoxHeight) else boxH
        if (size < MIN_FONT_SIZE) return null
        return PlacedLine(line.text, x, baselineY, size)
    }
}

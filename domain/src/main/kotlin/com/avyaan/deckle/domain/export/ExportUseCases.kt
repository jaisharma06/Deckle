package com.avyaan.deckle.domain.export

import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageFileStore
import javax.inject.Inject

/**
 * FR-52: estimated output size before exporting.
 * ORIGINAL is exact (sum of files); SMALL/MEDIUM re-encode one page and extrapolate by pixel count.
 */
public class EstimateExportSizeUseCase @Inject constructor(
    private val files: PageFileStore,
    private val probe: JpegSizeProbe,
) {
    public suspend operator fun invoke(pages: List<Page>, quality: QualityPreset, format: ExportFormat): Long {
        if (pages.isEmpty()) return 0
        val overhead = if (format == ExportFormat.PDF) pages.sumOf { PDF_PAGE_OVERHEAD + it.lineCount() * BYTES_PER_LINE } else 0L
        if (format == ExportFormat.PNG) return pages.sumOf { it.widthPx.toLong() * it.heightPx } * PNG_BYTES_PER_PIXEL_X100 / 100
        if (quality == QualityPreset.ORIGINAL) return pages.sumOf { files.sizeOf(it.processedPath) } + overhead

        val sample = pages.first()
        val sampleBytes = probe.encodedSize(sample.processedPath, quality) ?: return pages.sumOf { files.sizeOf(it.processedPath) } + overhead
        val samplePixels = targetPixels(sample, quality).coerceAtLeast(1)
        val bytesPerPixel = sampleBytes.toDouble() / samplePixels
        return (pages.sumOf { targetPixels(it, quality) } * bytesPerPixel).toLong() + overhead
    }

    private fun targetPixels(p: Page, q: QualityPreset): Long {
        val long = maxOf(p.widthPx, p.heightPx)
        val scale = q.maxLongEdge?.let { minOf(1.0, it.toDouble() / long) } ?: 1.0
        return (p.widthPx * scale).toLong() * (p.heightPx * scale).toLong()
    }

    private fun Page.lineCount() = ocr.lines?.size ?: 0

    private companion object {
        const val PDF_PAGE_OVERHEAD = 2_048L
        const val BYTES_PER_LINE = 40L
        const val PNG_BYTES_PER_PIXEL_X100 = 150L   // ~1.5 B/px for scanned documents
    }
}

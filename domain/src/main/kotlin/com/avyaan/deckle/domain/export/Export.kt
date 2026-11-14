package com.avyaan.deckle.domain.export

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.ocr.OcrLine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** FR-51. AUTO follows the image aspect ratio; A4 and LETTER letterbox the image, never stretch it. */
@Serializable
public enum class PageSizePolicy { AUTO, A4, LETTER }

/** FR-52. ORIGINAL embeds the processed JPEG bytes untouched. */
@Serializable
public enum class QualityPreset(public val maxLongEdge: Int?, public val jpegQuality: Int?) {
    SMALL(1600, 60), MEDIUM(2400, 75), ORIGINAL(null, null)
}

@Serializable
public enum class ExportFormat { PDF, JPEG, PNG }

/** Destination chosen by the user. */
@Serializable
public sealed interface ExportTarget {
    /** Write to app cache and hand to the share sheet (FR-54). */
    @Serializable public data object Share : ExportTarget
    /** A document Uri from CreateDocument (PDF). */
    @Serializable public data class File(val uri: String) : ExportTarget
    /** A tree Uri from OpenDocumentTree (images, FR-53). */
    @Serializable public data class Folder(val treeUri: String) : ExportTarget
}

/** Everything an export needs except the password, which is never persisted (FR-55, spec 04 §5.2). */
@Serializable
public data class ExportRequest(
    val exportId: String,
    val documentIds: List<String>,
    /** Null = all pages of each document. Only meaningful with one document. */
    val pageIds: List<String>? = null,
    val format: ExportFormat = ExportFormat.PDF,
    val pageSize: PageSizePolicy = PageSizePolicy.AUTO,
    val quality: QualityPreset = QualityPreset.MEDIUM,
    val hasPassword: Boolean = false,
    val target: ExportTarget = ExportTarget.Share,
    /** When set, every produced file is packed into one ZIP (folder export). */
    val archive: ExportArchive? = null,
    /** NV-43: text drawn diagonally across every exported page; null or blank = none. */
    val watermark: String? = null,
)

/**
 * A ZIP bundle: [name] without extension, and each document's directory inside the archive
 * (documents not listed sit at the top level).
 */
@Serializable
public data class ExportArchive(
    val name: String,
    val directories: Map<String, String> = emptyMap(),
) {
    public companion object {
        public const val MIME: String = "application/zip"
    }
}

public sealed interface ExportProgress {
    public data object Queued : ExportProgress
    public data class Running(val done: Int, val total: Int) : ExportProgress
    public data class Succeeded(val outputs: List<String>, val mimeType: String) : ExportProgress
    public data class Failed(val error: DeckleError) : ExportProgress
}

public interface ExportScheduler {
    public fun enqueue(request: ExportRequest, password: CharArray?)
    public fun observe(exportId: String): Flow<ExportProgress>
    public fun cancel(exportId: String)
}

/** One page handed to the PDF composer. */
public data class PdfPageInput(
    val imagePath: String,
    val widthPx: Int,
    val heightPx: Int,
    val lines: List<OcrLine>,
)

public data class PdfRequest(
    val pages: List<PdfPageInput>,
    val pageSize: PageSizePolicy,
    val quality: QualityPreset,
    val password: CharArray?,
)

public interface PdfComposer {
    public suspend fun compose(request: PdfRequest, out: String, onProgress: (done: Int, total: Int) -> Unit): Outcome<Long>
}

/** Re-encodes one image at a preset and reports the byte size; used for estimates. */
public interface JpegSizeProbe {
    public suspend fun encodedSize(imagePath: String, quality: QualityPreset): Long?
}

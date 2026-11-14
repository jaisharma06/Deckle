package com.avyaan.deckle.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.avyaan.deckle.data.files.FileLayout
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TitleRules
import com.avyaan.deckle.domain.export.ExportArchive
import com.avyaan.deckle.domain.export.ExportFormat
import com.avyaan.deckle.domain.export.ExportRequest
import com.avyaan.deckle.domain.export.ExportTarget
import com.avyaan.deckle.domain.export.PdfComposer
import com.avyaan.deckle.domain.export.PdfPageInput
import com.avyaan.deckle.domain.export.PdfRequest
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What an export produced: cache paths for Share, content URIs for File/Folder targets. */
public data class ExportOutput(
    val outputs: List<String>,
    val mimeType: String,
    /** Titles of the documents actually exported (missing or empty ones are skipped), for history. */
    val titles: List<String> = emptyList(),
    val pageCount: Int = 0,
)

/**
 * Runs one export inside the worker (FR-50…55). Everything is written to `cache/exports/<id>/` first;
 * for "Save to device" it is then copied to the user's chosen location and the cache copy removed.
 * One PDF per document; images are named "<title> - 01.jpg" with zero-padding (spec 04 §4).
 */
public class ExportRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documents: DocumentRepository,
    private val pages: PageRepository,
    private val composer: PdfComposer,
    private val bitmaps: BitmapIO,
    private val layout: FileLayout,
    private val dispatchers: DispatcherProvider,
    private val compositor: MarkupCompositor,
) {
    public suspend fun run(request: ExportRequest, password: CharArray?, onProgress: (done: Int, total: Int) -> Unit): Outcome<ExportOutput> {
        val jobs = request.documentIds.mapNotNull { id ->
            val doc = documents.get(id) ?: return@mapNotNull null
            val all = pages.pages(id).sortedBy { it.orderIndex }
            val chosen = request.pageIds?.takeIf { request.documentIds.size == 1 }?.let { ids -> all.filter { it.id in ids } } ?: all
            Job(id, doc.title, chosen)
        }.filter { it.pages.isNotEmpty() }
        if (jobs.isEmpty()) return Outcome.Failure(DeckleError.NotFound)

        val dir = File(layout.exportsDir, request.exportId).apply { deleteRecursively(); mkdirs() }
        val archive = request.archive
        // A folder export writes its parts below `parts/`, mirroring the folder tree, then packs them.
        val partsRoot = if (archive != null) File(dir, PARTS) else dir
        // Pages with markup or a watermark are composited here first (NV-40…43), then removed.
        val markupDir = File(dir, MARKUP)
        try {
            return runJobs(request, password, jobs, dir, partsRoot, markupDir, onProgress)
        } finally {
            markupDir.deleteRecursively()
        }
    }

    private suspend fun runJobs(
        request: ExportRequest,
        password: CharArray?,
        jobs: List<Job>,
        dir: File,
        partsRoot: File,
        markupDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Outcome<ExportOutput> {
        val total = jobs.sumOf { it.pages.size }
        var done = 0
        val names = UniqueNames()
        val produced = mutableListOf<File>()
        val archive = request.archive

        for ((docId, title, docPages) in jobs) {
            val subdir = archive?.directories?.get(docId).orEmpty()
            val outDir = if (subdir.isEmpty()) partsRoot else File(partsRoot, subdir).apply { mkdirs() }
            when (request.format) {
                ExportFormat.PDF -> {
                    val out = File(outDir, names.next(TitleRules.fileName(title), "pdf", subdir))
                    val inputs = try {
                        withContext(dispatchers.io) { docPages.map { it.toPdfInput(request, markupDir) } }
                    } catch (e: Exception) {
                        return Outcome.Failure(DeckleError.ExportFailed(e.javaClass.simpleName))
                    }
                    val pdf = PdfRequest(inputs, request.pageSize, request.quality, password)
                    val base = done
                    when (val r = composer.compose(pdf, out.path) { d, _ -> onProgress(base + d, total) }) {
                        is Outcome.Failure -> return r
                        is Outcome.Success -> produced += out
                    }
                    done += docPages.size
                }
                ExportFormat.JPEG, ExportFormat.PNG -> {
                    val width = docPages.size.toString().length.coerceAtLeast(2)
                    docPages.forEachIndexed { i, page ->
                        val ext = if (request.format == ExportFormat.PNG) "png" else "jpg"
                        // A named page exports under its own name; UniqueNames keeps duplicates apart.
                        val stem = page.name?.let(TitleRules::fileName)
                            ?: "${TitleRules.fileName(title)} - ${(i + 1).toString().padStart(width, '0')}"
                        val name = names.next(stem, ext, subdir)
                        val out = File(outDir, name)
                        if (!writeImage(page, out, request, markupDir)) return Outcome.Failure(DeckleError.ExportFailed("encode"))
                        produced += out
                        onProgress(++done, total)
                    }
                }
            }
        }

        val mime = when {
            archive != null -> ExportArchive.MIME
            request.format == ExportFormat.PDF -> "application/pdf"
            request.format == ExportFormat.JPEG -> "image/jpeg"
            else -> "image/png"
        }
        val deliverables = if (archive == null) produced else {
            val zip = File(dir, "${archive.name}.zip")
            try {
                ZipPacker.pack(partsRoot, produced, zip, dispatchers)
            } catch (e: Exception) {
                return Outcome.Failure(DeckleError.ExportFailed(e.javaClass.simpleName))
            } finally {
                partsRoot.deleteRecursively()
            }
            listOf(zip)
        }
        return when (val delivered = deliver(request.target, deliverables, mime)) {
            is Outcome.Success -> Outcome.Success(delivered.value.copy(titles = jobs.map { it.title }, pageCount = total))
            is Outcome.Failure -> delivered
        }
    }

    private suspend fun writeImage(page: Page, out: File, request: ExportRequest, markupDir: File): Boolean = withContext(dispatchers.io) {
        coroutineContext.ensureActive()
        val q = request.quality
        val maxEdge = q.maxLongEdge.takeIf { request.format == ExportFormat.JPEG }
        val prepared = runCatching { compositor.prepare(page, request.watermark, maxEdge, markupDir) }.getOrNull() ?: return@withContext false
        if (request.format == ExportFormat.JPEG && q.maxLongEdge == null) {
            // Original quality: the processed (or composited) file already is the JPEG we want.
            File(prepared.imagePath).copyTo(out, overwrite = true)
            return@withContext true
        }
        val bitmap = bitmaps.decodeFile(prepared.imagePath, maxEdge) ?: return@withContext false
        try {
            if (request.format == ExportFormat.PNG) bitmaps.writePng(bitmap, out.path)
            else bitmaps.writeJpeg(bitmap, out.path, q.jpegQuality ?: ORIGINAL_JPEG_QUALITY)
            true
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun deliver(target: ExportTarget, files: List<File>, mime: String): Outcome<ExportOutput> = withContext(dispatchers.io) {
        try {
            when (target) {
                ExportTarget.Share -> Outcome.Success(ExportOutput(files.map { it.path }, mime))
                is ExportTarget.File -> {
                    val uri = Uri.parse(target.uri)
                    val file = files.single()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: return@withContext Outcome.Failure(DeckleError.ExportFailed("open"))
                    file.delete()
                    Outcome.Success(ExportOutput(listOf(target.uri), mime))
                }
                is ExportTarget.Folder -> {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(target.treeUri))
                        ?: return@withContext Outcome.Failure(DeckleError.ExportFailed("tree"))
                    val written = files.map { file ->
                        val doc = tree.createFile(mime, file.name) ?: error("createFile failed")
                        context.contentResolver.openOutputStream(doc.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        file.delete()
                        doc.uri.toString()
                    }
                    Outcome.Success(ExportOutput(written, mime))
                }
            }
        } catch (e: Exception) {
            Outcome.Failure(DeckleError.ExportFailed(e.javaClass.simpleName))
        }
    }

    /** Burns markup and the watermark in (when there are any) and drops redacted text from the text layer. */
    private fun Page.toPdfInput(request: ExportRequest, markupDir: File): PdfPageInput {
        val prepared = compositor.prepare(this, request.watermark, null, markupDir)
        return PdfPageInput(prepared.imagePath, widthPx, heightPx, prepared.lines)
    }

    private data class Job(val documentId: String, val title: String, val pages: List<Page>)

    /** Two documents with the same title in the same directory must not overwrite each other's file. */
    private class UniqueNames {
        private val used = HashSet<String>()
        fun next(stem: String, ext: String, dir: String = ""): String {
            var name = "$stem.$ext"
            var n = 2
            while (!used.add("$dir/$name".lowercase())) name = "$stem ($n).$ext".also { n++ }
            return name
        }
    }

    private companion object {
        const val ORIGINAL_JPEG_QUALITY = 95
        const val PARTS = "parts"
        const val MARKUP = ".markup"
    }
}

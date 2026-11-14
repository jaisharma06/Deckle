package com.avyaan.deckle.domain.page

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.common.newRevision
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TransactionRunner
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.QuadRotation
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageRotator
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.imaging.ThumbnailMaker
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.ocr.toPlainText
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

public class ObservePagesUseCase @Inject constructor(private val pages: PageRepository) {
    public operator fun invoke(documentId: String): Flow<List<Page>> = pages.observePages(documentId)
}

/** FR-40: drag to reorder, persisted immediately. Usually one row is written. */
/**
 * Names (or un-names) a page. A blank name clears it, so the page falls back to "Page N"; names use the
 * same cleaning rules as document titles. Touches the document so it sorts as recently modified.
 */
public class RenamePageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String, raw: String) {
        val page = pages.get(pageId) ?: return
        val name = com.avyaan.deckle.domain.document.TitleRules.clean(raw)
        if (name == page.name) return
        pages.rename(pageId, name)
        documents.touch(page.documentId, clock.now())
    }
}

public class ReorderPageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val documents: DocumentRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    public suspend operator fun invoke(documentId: String, pageId: String, toIndex: Int) {
        val ordered = pages.pages(documentId).sortedBy { it.orderIndex }
        val others = ordered.filterNot { it.id == pageId }
        if (others.size == ordered.size) return
        val target = toIndex.coerceIn(0, others.size)
        tx.inTransaction {
            val index = OrderIndexAllocator.forMove(others.map { it.orderIndex }, target)
            if (index != null) {
                pages.updateOrder(pageId, index)
            } else {
                val ids = others.map { it.id }.toMutableList().apply { add(target, pageId) }
                pages.updateOrders(OrderIndexAllocator.renumber(ids))
            }
            documents.touch(documentId, clock.now())
        }
    }
}

/**
 * FR-42 delete. The UI defers calling this until the undo snackbar expires.
 * Deleting a document's last page deletes the document too: a document with no pages has nothing to show
 * or export, and the pages are already gone for good, so it isn't kept in the trash either.
 * Returns true if the document was removed.
 */
public class DeletePageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val documents: DocumentRepository,
    private val files: PageFileStore,
    private val ocr: OcrScheduler,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val deleteDocuments: com.avyaan.deckle.domain.document.DeleteDocumentsPermanentlyUseCase,
) {
    public suspend operator fun invoke(pageId: String): Boolean {
        val page = pages.get(pageId) ?: return false
        ocr.cancel(pageId)
        tx.inTransaction {
            pages.delete(pageId)
            val doc = documents.get(page.documentId)
            if (doc?.coverPageId == pageId) documents.setCover(page.documentId, null)
            documents.touch(page.documentId, clock.now())
        }
        page.allPaths().forEach { files.delete(it) }
        if (pages.pages(page.documentId).isNotEmpty()) return false
        deleteDocuments(listOf(page.documentId))
        return true
    }
}

/**
 * NV-02: an independent copy of a page, placed right after it. Files are copied under fresh paths so
 * editing either page never touches the other. Recognised text is copied too (no second OCR run);
 * a page whose OCR hasn't finished gets its own OCR job. Returns the new page id.
 */
public class DuplicatePageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val documents: DocumentRepository,
    private val files: PageFileStore,
    private val ocr: OcrScheduler,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String): String? {
        val page = pages.get(pageId) ?: return null
        val newId = ids.newId()
        val rev = ids.newRevision()
        fun path(kind: PageFileKind) = files.pathFor(page.documentId, newId, kind, rev)
        val copies = listOfNotNull(
            page.sourcePath?.let { it to path(PageFileKind.SOURCE) },
            page.basePath to path(PageFileKind.BASE),
            page.processedPath to path(PageFileKind.PROCESSED),
            page.thumbPath to path(PageFileKind.THUMB),
        )
        val written = mutableListOf<String>()
        try {
            copies.forEach { (from, to) -> files.copy(from, to); written += to }
        } catch (e: Exception) {
            written.forEach { files.delete(it) }
            throw e
        }
        val copiedPath = copies.toMap()
        val done = page.ocr.status == OcrStatus.DONE
        val copy = page.copy(
            id = newId,
            sourcePath = page.sourcePath?.let(copiedPath::getValue),
            basePath = copiedPath.getValue(page.basePath),
            processedPath = copiedPath.getValue(page.processedPath),
            thumbPath = copiedPath.getValue(page.thumbPath),
            ocr = if (done) page.ocr else OcrState.QUEUED,
        )
        try {
            tx.inTransaction {
                val ordered = pages.pages(page.documentId).sortedBy { it.orderIndex }
                val next = ordered.getOrNull(ordered.indexOfFirst { it.id == pageId } + 1)
                val index = OrderIndexAllocator.between(page.orderIndex, next?.orderIndex)
                pages.insert(listOf(copy.copy(orderIndex = index ?: page.orderIndex)))
                if (index == null) {
                    val ids = ordered.map { it.id }.toMutableList().apply { add(indexOf(pageId) + 1, newId) }
                    pages.updateOrders(OrderIndexAllocator.renumber(ids))
                }
                if (done) pages.setOcr(newId, page.ocr, page.ocr.lines?.toPlainText())
                documents.touch(page.documentId, clock.now())
            }
        } catch (e: Exception) {
            written.forEach { files.delete(it) }
            throw e
        }
        if (!done) ocr.enqueue(listOf(newId))
        return newId
    }
}

/**
 * Shared steps for edits that re-render a committed page: write new revisions, update the row,
 * then delete the old files. A crash part-way leaves the old row pointing at intact old files.
 */
public class PageRenderer @Inject constructor(
    private val files: PageFileStore,
    private val enhancer: PageEnhancer,
    private val thumbnails: ThumbnailMaker,
    private val ids: IdGenerator,
) {
    public fun newPath(page: Page, kind: PageFileKind, rev: String): String = files.pathFor(page.documentId, page.id, kind, rev)

    public suspend fun enhanceAndThumb(page: Page, basePath: String, mode: EnhancementMode, adj: Adjustments, rev: String = ids.newRevision()): Outcome<Rendered> {
        val processed = newPath(page, PageFileKind.PROCESSED, rev)
        val size = when (val r = enhancer.enhance(basePath, mode, adj, processed)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val thumb = newPath(page, PageFileKind.THUMB, rev)
        thumbnails.make(processed, thumb)
        return Outcome.Success(Rendered(processed, thumb, size))
    }

    public suspend fun deleteReplaced(old: Page, new: Page) {
        (old.allPaths() - new.allPaths().toSet()).forEach { files.delete(it) }
    }

    public data class Rendered(val processedPath: String, val thumbPath: String, val size: ImageSize)
}

/** FR-42 re-enhance: always from the base image (FR-31). OCR is unaffected (it reads the base). */
public class ReEnhancePageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val renderer: PageRenderer,
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String, mode: EnhancementMode, adjustments: Adjustments): Outcome<Unit> {
        val page = pages.get(pageId) ?: return Outcome.Failure(DeckleError.NotFound)
        val r = when (val o = renderer.enhanceAndThumb(page, page.basePath, mode, adjustments)) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> return o
        }
        val updated = page.copy(mode = mode, adjustments = adjustments, processedPath = r.processedPath, thumbPath = r.thumbPath,
            widthPx = r.size.width, heightPx = r.size.height)
        pages.update(updated)
        documents.touch(page.documentId, clock.now())
        renderer.deleteReplaced(page, updated)
        return Outcome.Success(Unit)
    }
}

/** FR-42 rotate: turns the base and processed images; OCR boxes are rotated analytically (no re-OCR). */
public class RotatePageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val rotator: ImageRotator,
    private val thumbnails: ThumbnailMaker,
    private val renderer: PageRenderer,
    private val documents: DocumentRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String, clockwise: Boolean): Outcome<Unit> {
        val page = pages.get(pageId) ?: return Outcome.Failure(DeckleError.NotFound)
        val turn = if (clockwise) Rotation.R90 else Rotation.R270
        val rev = ids.newRevision()
        val base = renderer.newPath(page, PageFileKind.BASE, rev)
        val processed = renderer.newPath(page, PageFileKind.PROCESSED, rev)
        val thumb = renderer.newPath(page, PageFileKind.THUMB, rev)

        if (rotator.rotate(page.basePath, turn, base) is Outcome.Failure) return Outcome.Failure(DeckleError.ImageDecodeFailed)
        val size = when (val r = rotator.rotate(page.processedPath, turn, processed)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        thumbnails.make(processed, thumb)

        val lines = page.ocr.lines?.map { it.copy(box = QuadRotation.rotate(it.box, turn)) }
        val updated = page.copy(
            basePath = base, processedPath = processed, thumbPath = thumb,
            widthPx = size.width, heightPx = size.height,
            // Corners stay against the unrotated source; only the rotation changes (spec 03 §9).
            rotation = if (clockwise) page.rotation.clockwise() else page.rotation.counterClockwise(),
            ocr = page.ocr.copy(lines = lines),
        )
        pages.update(updated)
        documents.touch(page.documentId, clock.now())
        renderer.deleteReplaced(page, updated)
        return Outcome.Success(Unit)
    }
}

/** FR-42 re-crop: needs the saved original. Base changes, so OCR is re-run. */
public class RecropPageUseCase @Inject constructor(
    private val pages: PageRepository,
    private val corrector: PerspectiveCorrector,
    private val renderer: PageRenderer,
    private val ocr: OcrScheduler,
    private val documents: DocumentRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(pageId: String, quad: Quad, rotation: Rotation): Outcome<Unit> {
        val page = pages.get(pageId) ?: return Outcome.Failure(DeckleError.NotFound)
        val source = page.sourcePath ?: return Outcome.Failure(DeckleError.OriginalNotSaved)
        val rev = ids.newRevision()
        val base = renderer.newPath(page, PageFileKind.BASE, rev)
        if (corrector.correct(source, quad, rotation, base) is Outcome.Failure) return Outcome.Failure(DeckleError.ImageDecodeFailed)
        val r = when (val o = renderer.enhanceAndThumb(page, base, page.mode, page.adjustments, rev)) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> return o
        }
        val updated = page.copy(
            basePath = base, processedPath = r.processedPath, thumbPath = r.thumbPath,
            widthPx = r.size.width, heightPx = r.size.height,
            corners = quad, rotation = rotation, ocr = OcrState.QUEUED,
        )
        pages.update(updated)
        pages.setOcr(pageId, OcrState.QUEUED, plainText = null)
        documents.touch(page.documentId, clock.now())
        renderer.deleteReplaced(page, updated)
        ocr.enqueue(listOf(pageId))
        return Outcome.Success(Unit)
    }
}

/** FR-45. */
public class GetDocumentTextUseCase @Inject constructor(private val pages: PageRepository) {
    public suspend operator fun invoke(documentId: String): DocumentText = pages.documentText(documentId)
}

public fun Page.allPaths(): List<String> = listOfNotNull(sourcePath, basePath, processedPath, thumbPath)

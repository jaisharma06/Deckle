package com.avyaan.deckle.domain.scan

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.document.DefaultTitle
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TransactionRunner
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.CardComposer
import com.avyaan.deckle.domain.imaging.ThumbnailMaker
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.page.OrderIndexAllocator
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageFileKind
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * FR-34 "Done": atomically turns a ready scan session into document pages (spec 02 §5.1).
 * Files are moved first and moved back if the database write fails, so nothing is orphaned.
 */
public class CommitScanSessionUseCase @Inject constructor(
    private val sessions: ScanSessionStore,
    private val documents: DocumentRepository,
    private val pages: PageRepository,
    private val files: PageFileStore,
    private val thumbnails: ThumbnailMaker,
    private val tx: TransactionRunner,
    private val ocr: OcrScheduler,
    private val settings: SettingsRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val cards: CardComposer,
) {
    /** Returns the document id the pages were saved into. */
    public suspend operator fun invoke(sessionId: String): Outcome<String> {
        val session = sessions.get(sessionId) ?: return Outcome.Failure(DeckleError.NotFound)
        if (!session.isReadyToCommit) return Outcome.Failure(DeckleError.NotFound)
        val saveOriginals = settings.settings.first().saveOriginals
        val now = clock.now()

        val documentId = session.targetDocumentId?.takeIf { documents.get(it) != null } ?: ids.newId()
        val isNewDocument = documentId != session.targetDocumentId
        val units = units(session.captures)
        val indices = allocateIndices(documentId, session.insertAfterPageId, units.size)

        val moves = mutableListOf<Pair<String, String>>()   // (from, to) for rollback
        suspend fun move(from: String, to: String): String { files.move(from, to); moves += from to to; return to }

        return try {
            val newPages = units.mapIndexed { i, unit ->
                val pageId = ids.newId()
                if (unit is SaveUnit.Card) {
                    val base = composeCard(sessionId, unit, "base") { it.basePath!! }
                    val processed = composeCard(sessionId, unit, "processed") { it.processedPath!! }
                    val thumb = files.pathFor(documentId, pageId, PageFileKind.THUMB)
                    val processedPath = move(processed.first, files.pathFor(documentId, pageId, PageFileKind.PROCESSED))
                    thumbnails.make(processedPath, thumb)
                    return@mapIndexed Page(
                        id = pageId,
                        documentId = documentId,
                        orderIndex = indices[i],
                        sourcePath = null,                      // a composite has no single original to re-crop
                        basePath = move(base.first, files.pathFor(documentId, pageId, PageFileKind.BASE)),
                        processedPath = processedPath,
                        thumbPath = thumb,
                        corners = Quad.FULL,
                        rotation = Rotation.R0,
                        mode = unit.first.mode,
                        adjustments = unit.first.adjustments,
                        widthPx = processed.second.width,
                        heightPx = processed.second.height,
                        ocr = OcrState.QUEUED,
                    )
                }
                val c = (unit as SaveUnit.Single).capture
                val processed = move(c.processedPath!!, files.pathFor(documentId, pageId, PageFileKind.PROCESSED))
                val thumb = files.pathFor(documentId, pageId, PageFileKind.THUMB)
                thumbnails.make(processed, thumb)
                Page(
                    id = pageId,
                    documentId = documentId,
                    orderIndex = indices[i],
                    sourcePath = if (saveOriginals) move(c.sourcePath, files.pathFor(documentId, pageId, PageFileKind.SOURCE)) else null,
                    basePath = move(c.basePath!!, files.pathFor(documentId, pageId, PageFileKind.BASE)),
                    processedPath = processed,
                    thumbPath = thumb,
                    corners = c.quad,
                    rotation = c.rotation,
                    mode = c.mode,
                    adjustments = c.adjustments,
                    widthPx = c.widthPx,
                    heightPx = c.heightPx,
                    ocr = OcrState.QUEUED,
                )
            }
            tx.inTransaction {
                if (isNewDocument) documents.create(documentId, DefaultTitle.at(now, clock.zone()), now, session.targetFolderId)
                pages.insert(newPages)
                documents.touch(documentId, now)
            }
            ocr.enqueue(newPages.map { it.id })
            sessions.discard(sessionId)
            Outcome.Success(documentId)
        } catch (e: CancellationException) {
            rollback(moves)
            throw e
        } catch (e: Exception) {
            rollback(moves)
            Outcome.Failure(DeckleError.Unexpected(e))
        }
    }

    /** What becomes one page: a capture, or both sides of an ID card (NV-11), in scan order. */
    private sealed interface SaveUnit {
        data class Single(val capture: StagedCapture) : SaveUnit
        data class Card(val first: StagedCapture, val second: StagedCapture?) : SaveUnit
    }

    private fun units(captures: List<StagedCapture>): List<SaveUnit> {
        val seen = mutableSetOf<String>()
        return captures.mapNotNull { c ->
            val group = c.group
            val isCard = c.kind == CaptureKind.ID_FRONT || c.kind == CaptureKind.ID_BACK
            if (group == null || !isCard) return@mapNotNull SaveUnit.Single(c)
            if (!seen.add(group)) return@mapNotNull null
            val sides = captures.filter { it.group == group }.sortedBy { if (it.kind == CaptureKind.ID_FRONT) 0 else 1 }
            SaveUnit.Card(sides.first(), sides.getOrNull(1))
        }
    }

    /** Writes the composite into staging (cleared with the session) and returns it with its size. */
    private suspend fun composeCard(sessionId: String, card: SaveUnit.Card, kind: String, path: (StagedCapture) -> String): Pair<String, ImageSize> {
        val out = files.stagingPath(sessionId, "card-${card.first.id}", "$kind-${ids.newId().take(8)}")
        return when (val r = cards.compose(path(card.first), card.second?.let(path), out)) {
            is Outcome.Success -> out to r.value
            is Outcome.Failure -> throw IllegalStateException("Card composition failed: ${r.error}")
        }
    }

    private suspend fun allocateIndices(documentId: String, insertAfter: String?, count: Int): List<Long> {
        val existing = pages.pages(documentId).sortedBy { it.orderIndex }
        val anchor = existing.indexOfFirst { it.id == insertAfter }
        val before = if (anchor >= 0) existing[anchor].orderIndex else existing.lastOrNull()?.orderIndex
        val after = if (anchor >= 0) existing.getOrNull(anchor + 1)?.orderIndex else null
        return when (val a = OrderIndexAllocator.allocate(before, after, count)) {
            is OrderIndexAllocator.Allocation.Indices -> a.values
            OrderIndexAllocator.Allocation.Renumber -> {
                pages.updateOrders(OrderIndexAllocator.renumber(existing.map { it.id }))
                allocateIndices(documentId, insertAfter, count)
            }
        }
    }

    private suspend fun rollback(moves: List<Pair<String, String>>) {
        moves.asReversed().forEach { (from, to) -> runCatching { files.move(to, from) } }
    }
}

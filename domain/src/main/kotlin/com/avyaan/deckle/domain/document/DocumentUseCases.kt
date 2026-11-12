package com.avyaan.deckle.domain.document

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.page.OrderIndexAllocator
import com.avyaan.deckle.domain.page.PageFileKind
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.settings.SettingsRepository
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.domain.folder.FolderRepository
import com.avyaan.deckle.domain.folder.FolderSummary
import com.avyaan.deckle.domain.folder.FolderTree

/**
 * FR-1/2/3: one library level (the root or a folder), re-queried whenever the sort, the query or the
 * folders change. Searching looks through every folder: a search box that only sees the current folder
 * hides documents from people who don't remember where they put them.
 */
public class ObserveLibraryUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val settings: SettingsRepository,
) {
    public operator fun invoke(query: String, folderId: String?): Flow<LibraryListing> =
        settings.settings.flatMapLatest { s ->
            val docs = if (query.isBlank()) documents.observeLibrary(s.sortOrder, folderId)
            else documents.search(SearchQuery.toLike(query), SearchQuery.toFts(query), s.sortOrder)
            combine(docs, folders.observeAll(), folders.observeDocumentCounts()) { list, all, counts ->
                val shown = if (query.isBlank()) all.filter { it.parentId == folderId }
                else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
                LibraryListing(
                    folders = shown.sortedWith(s.sortOrder.folderComparator()).map { f ->
                        FolderSummary(f, documentCount = counts[f.id] ?: 0, folderCount = all.count { it.parentId == f.id })
                    },
                    documents = list,
                    path = FolderTree.path(folderId, all),
                    allFolders = all,
                )
            }
        }

    private fun SortOrder.folderComparator(): Comparator<Folder> = when (this) {
        SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
        SortOrder.MODIFIED_DESC -> compareByDescending { it.modifiedAt }
        SortOrder.TITLE_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}

/** One library level. [path] is root-first and empty at the root; [allFolders] feeds the move picker. */
public data class LibraryListing(
    val folders: List<FolderSummary>,
    val documents: List<DocumentSummary>,
    val path: List<Folder>,
    val allFolders: List<Folder>,
)

public class ObserveDocumentUseCase @Inject constructor(private val documents: DocumentRepository) {
    public operator fun invoke(id: String): Flow<Document?> = documents.observeDocument(id)
}

public class ObserveTrashUseCase @Inject constructor(private val documents: DocumentRepository) {
    public operator fun invoke(): Flow<List<DocumentSummary>> = documents.observeTrash()
}

/** FR-43. Returns false when the title is blank (the UI reverts and shakes). */
public class RenameDocumentUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(id: String, raw: String): Boolean {
        val title = TitleRules.clean(raw) ?: return false
        documents.rename(id, title, clock.now())
        return true
    }
}

public class SetCoverPageUseCase @Inject constructor(private val documents: DocumentRepository) {
    public suspend operator fun invoke(documentId: String, pageId: String): Unit = documents.setCover(documentId, pageId)
}

/** FR-7: move to Recently Deleted. Reversible with [RestoreDocumentsUseCase]. */
public class MoveToTrashUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(ids: List<String>): Unit = documents.softDelete(ids, clock.now())
}

public class RestoreDocumentsUseCase @Inject constructor(private val documents: DocumentRepository) {
    public suspend operator fun invoke(ids: List<String>): Unit = documents.restore(ids)
}

/**
 * Permanent delete: rows and folder together (spec 02 §4.3 tombstone protocol).
 * 1. rename the folder to a tombstone, 2. delete rows, 3. delete the tombstone — or rename it back.
 */
public class DeleteDocumentsPermanentlyUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val files: PageFileStore,
    private val tx: TransactionRunner,
) {
    public suspend operator fun invoke(ids: List<String>) {
        ids.forEach { id ->
            val tombstone = files.tombstoneDocument(id)
            try {
                tx.inTransaction { documents.deleteRows(id) }
            } catch (e: Exception) {
                tombstone?.let { files.revertTombstone(it) }
                throw e
            }
            tombstone?.let { files.commitTombstone(it) }
        }
    }
}

/** FR-7: purge documents deleted more than 30 days ago. Runs on launch. */
public class PurgeTrashUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val deletePermanently: DeleteDocumentsPermanentlyUseCase,
    private val clock: Clock,
) {
    public suspend operator fun invoke() {
        val cutoff = clock.now().minus(RETENTION)
        deletePermanently(documents.idsDeletedBefore(cutoff))
    }

    public companion object {
        public val RETENTION: Duration = Duration.ofDays(30)
    }
}

/**
 * FR-4 merge: a new document with every page of the selection, in selection order.
 * Page files are moved into the new folder; the emptied source documents are removed.
 */
public class MergeDocumentsUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pages: PageRepository,
    private val files: PageFileStore,
    private val tx: TransactionRunner,
    private val deletePermanently: DeleteDocumentsPermanentlyUseCase,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(documentIds: List<String>): Outcome<String> {
        if (documentIds.size < 2) return Outcome.Failure(DeckleError.NotFound)
        val first = documents.get(documentIds.first()) ?: return Outcome.Failure(DeckleError.NotFound)
        val newId = ids.newId()
        val now = clock.now()
        val allPages = documentIds.flatMap { id -> pages.pages(id).sortedBy { it.orderIndex } }
        val indices = OrderIndexAllocator.initial(allPages.size)

        val moved = allPages.mapIndexed { i, p ->
            fun dest(path: String?, kind: PageFileKind) = path?.let { files.pathFor(newId, p.id, kind) }
            val source = dest(p.sourcePath, PageFileKind.SOURCE)
            val base = dest(p.basePath, PageFileKind.BASE)!!
            val processed = dest(p.processedPath, PageFileKind.PROCESSED)!!
            val thumb = dest(p.thumbPath, PageFileKind.THUMB)!!
            p.sourcePath?.let { files.move(it, source!!) }
            files.move(p.basePath, base)
            files.move(p.processedPath, processed)
            files.move(p.thumbPath, thumb)
            p.copy(documentId = newId, orderIndex = indices[i], sourcePath = source, basePath = base, processedPath = processed, thumbPath = thumb)
        }
        tx.inTransaction {
            documents.create(newId, first.title, now, first.folderId)
            moved.forEach { pages.update(it) }
            documents.touch(newId, now)
        }
        deletePermanently(documentIds)
        return Outcome.Success(newId)
    }
}

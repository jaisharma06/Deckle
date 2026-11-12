package com.avyaan.deckle.data.db

import com.avyaan.deckle.data.db.Mappers.toDomain
import com.avyaan.deckle.data.db.Mappers.toEntity
import com.avyaan.deckle.domain.document.Document
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.DocumentSummary
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.domain.folder.FolderRepository
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.DocumentText
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.export.ExportDestination
import com.avyaan.deckle.domain.export.ExportFormat
import com.avyaan.deckle.domain.export.ExportHistoryRepository
import com.avyaan.deckle.domain.export.ExportRecord
import java.time.Instant
import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

public class RoomDocumentRepository @Inject constructor(db: DeckleDatabase) : DocumentRepository {
    private val dao = db.documents()
    private val folderDao = db.folders()

    override fun observeLibrary(sort: SortOrder, folderId: String?): Flow<List<DocumentSummary>> = when (sort) {
        SortOrder.CREATED_DESC -> dao.libraryByCreated(folderId)
        SortOrder.MODIFIED_DESC -> dao.libraryByModified(folderId)
        SortOrder.TITLE_ASC -> dao.libraryByTitle(folderId)
    }.map { rows -> rows.map { it.toDomain() } }

    override fun search(titleQuery: String, ftsQuery: String?, sort: SortOrder): Flow<List<DocumentSummary>> {
        val rows = if (ftsQuery == null) dao.searchTitles(titleQuery) else dao.search(titleQuery, ftsQuery)
        return rows.map { list ->
            // One card per document: a title hit wins; otherwise the first text hit supplies the snippet.
            list.sortedBy { it.matchRank }
                .distinctBy { it.id }
                .map { it.toDomain() }
                .sortedWith(sort.comparator())
        }
    }

    override fun observeDocument(id: String): Flow<Document?> = dao.observe(id).map { it?.toDomain() }
    override fun observeTrash(): Flow<List<DocumentSummary>> = dao.trash().map { rows -> rows.map { it.toDomain() } }
    override suspend fun get(id: String): Document? = dao.get(id)?.toDomain()

    override suspend fun create(id: String, title: String, at: Instant, folderId: String?): Document {
        // The folder may have been deleted while the scan was open: then the document lands at the root.
        val folder = folderId?.takeIf { folderDao.exists(it) }
        val e = DocumentEntity(id, title, at.toEpochMilli(), at.toEpochMilli(), coverPageId = null, deletedAt = null, folderId = folder)
        dao.insert(e)
        return e.toDomain()
    }

    override suspend fun rename(id: String, title: String, at: Instant): Unit = dao.rename(id, title, at.toEpochMilli())
    override suspend fun setCover(id: String, pageId: String?): Unit = dao.setCover(id, pageId)
    override suspend fun touch(id: String, at: Instant): Unit = dao.touch(id, at.toEpochMilli())
    override suspend fun softDelete(ids: List<String>, at: Instant): Unit = dao.softDelete(ids, at.toEpochMilli())
    override suspend fun restore(ids: List<String>): Unit = dao.restore(ids)
    override suspend fun deleteRows(id: String): Unit = dao.delete(id)
    override suspend fun idsDeletedBefore(cutoff: Instant): List<String> = dao.idsDeletedBefore(cutoff.toEpochMilli())
    override suspend fun allIds(): Set<String> = dao.allIds().toSet()
    override suspend fun moveToFolder(ids: List<String>, folderId: String?, at: Instant): Unit =
        ids.chunked(SQL_CHUNK).forEach { dao.moveToFolder(it, folderId) }
    override suspend fun liveIdsIn(folderIds: Collection<String>): List<String> =
        folderIds.toList().chunked(SQL_CHUNK).flatMap { dao.liveIdsIn(it) }

    private fun SortOrder.comparator(): Comparator<DocumentSummary> = when (this) {
        SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
        SortOrder.MODIFIED_DESC -> compareByDescending { it.modifiedAt }
        SortOrder.TITLE_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }
}

public class RoomPageRepository @Inject constructor(db: DeckleDatabase) : PageRepository {
    private val dao = db.pages()

    override fun observePages(documentId: String): Flow<List<Page>> =
        dao.observeByDocument(documentId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun pages(documentId: String): List<Page> = dao.byDocument(documentId).map { it.toDomain() }
    override suspend fun get(pageId: String): Page? = dao.get(pageId)?.toDomain()
    override suspend fun insert(pages: List<Page>): Unit = dao.insert(pages.map { it.toEntity(plainText = null) })

    override suspend fun update(page: Page) {
        // Preserve the stored plain text and name: they're owned by setOcr and rename, not by page edits
        // (an edit that started before a rename must not write the old name back).
        val stored = dao.get(page.id)
        dao.update(page.toEntity(stored?.ocrPlainText).copy(name = stored?.name))
    }

    override suspend fun rename(pageId: String, name: String?): Unit = dao.rename(pageId, name)

    override suspend fun updateOrder(pageId: String, orderIndex: Long): Unit = dao.updateOrder(pageId, orderIndex)
    override suspend fun updateOrders(orders: Map<String, Long>): Unit = orders.forEach { (id, i) -> dao.updateOrder(id, i) }
    override suspend fun moveToDocument(pageIds: List<String>, documentId: String): Unit = dao.moveToDocument(pageIds, documentId)
    override suspend fun delete(pageId: String): Unit = dao.delete(pageId)

    override suspend fun setOcr(pageId: String, state: OcrState, plainText: String?): Unit =
        dao.setOcr(pageId, state.status.name, state.attempts, Mappers.encodeLines(state.lines), plainText)

    override suspend fun pagesWithOcrStatus(statuses: Set<OcrStatus>): List<Page> =
        dao.withOcrStatus(statuses.map { it.name }).map { it.toDomain() }

    override suspend fun documentText(documentId: String): DocumentText {
        val rows = dao.byDocument(documentId)
        val text = rows.mapNotNull { it.ocrPlainText?.takeIf(String::isNotBlank) }.joinToString("\n\n")
        return DocumentText(text, pagesPending = rows.count { it.ocrStatus != OcrStatus.DONE.name })
    }

    override suspend fun allFilePaths(): Set<String> =
        dao.allPaths().flatMap { listOfNotNull(it.sourcePath, it.basePath, it.processedPath, it.thumbPath) }.toSet()
}

public class RoomExportHistoryRepository @Inject constructor(db: DeckleDatabase) : ExportHistoryRepository {
    private val dao = db.exportHistory()
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())

    override fun observe(): Flow<List<ExportRecord>> = dao.observe().map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(record: ExportRecord) {
        dao.insert(record.toEntity())
        dao.trim(ExportHistoryRepository.MAX_RECORDS)
    }

    override suspend fun delete(id: String): Unit = dao.delete(id)
    override suspend fun clear(): Unit = dao.clear()

    private fun ExportRecordEntity.toDomain() = ExportRecord(
        id = id,
        createdAt = Instant.ofEpochMilli(createdAt),
        titles = decode(titlesJson),
        documentIds = decode(documentIdsJson),
        pageCount = pageCount,
        format = runCatching { ExportFormat.valueOf(format) }.getOrDefault(ExportFormat.PDF),
        destination = runCatching { ExportDestination.valueOf(destination) }.getOrDefault(ExportDestination.SHARE),
        outputs = decode(outputsJson),
        mimeType = mimeType,
        passwordProtected = passwordProtected,
    )

    private fun ExportRecord.toEntity() = ExportRecordEntity(
        id = id,
        createdAt = createdAt.toEpochMilli(),
        titlesJson = json.encodeToString(strings, titles),
        documentIdsJson = json.encodeToString(strings, documentIds),
        pageCount = pageCount,
        format = format.name,
        destination = destination.name,
        outputsJson = json.encodeToString(strings, outputs),
        mimeType = mimeType,
        passwordProtected = passwordProtected,
    )

    private fun decode(value: String): List<String> = runCatching { json.decodeFromString(strings, value) }.getOrDefault(emptyList())
}

public class RoomFolderRepository @Inject constructor(private val db: DeckleDatabase) : FolderRepository {
    private val dao = db.folders()

    override fun observeAll(): Flow<List<Folder>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun all(): List<Folder> = dao.all().map { it.toDomain() }
    override fun observeDocumentCounts(): Flow<Map<String, Int>> = dao.observeCounts().map { rows -> rows.associate { it.folderId to it.count } }
    override suspend fun get(id: String): Folder? = dao.get(id)?.toDomain()
    override suspend fun create(folder: Folder): Unit = dao.insert(folder.toEntity())
    override suspend fun rename(id: String, name: String, at: Instant): Unit = dao.rename(id, name, at.toEpochMilli())
    override suspend fun move(ids: List<String>, parentId: String?, at: Instant): Unit =
        ids.chunked(SQL_CHUNK).forEach { dao.move(it, parentId, at.toEpochMilli()) }

    override suspend fun delete(ids: List<String>) {
        db.withTransaction {
            ids.chunked(SQL_CHUNK).forEach { chunk ->
                db.documents().detachFrom(chunk)
                dao.delete(chunk)
            }
        }
    }
}

/** Stays well under SQLite's bound-parameter limit for `IN (:ids)` lists. */
private const val SQL_CHUNK = 500

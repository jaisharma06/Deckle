package com.avyaan.deckle.domain.document

import java.time.Instant
import kotlinx.coroutines.flow.Flow

public data class Document(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val coverPageId: String?,
    val deletedAt: Instant?,
    /** Containing folder; null = library root (schema v3). */
    val folderId: String? = null,
)

/** What a library card shows (FR-1), plus the matching OCR snippet when searching (FR-3). */
public data class DocumentSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant?,
    val coverThumbPath: String?,
    val snippet: Snippet? = null,
)

/** A text excerpt with the ranges that matched the query. */
public data class Snippet(val text: String, val highlights: List<IntRange>)

/** FR-2. */
public enum class SortOrder { CREATED_DESC, MODIFIED_DESC, TITLE_ASC }

public interface DocumentRepository {
    /** Live documents directly inside [folderId] (null = the library root). */
    public fun observeLibrary(sort: SortOrder, folderId: String?): Flow<List<DocumentSummary>>
    public fun search(titleQuery: String, ftsQuery: String?, sort: SortOrder): Flow<List<DocumentSummary>>
    public fun observeDocument(id: String): Flow<Document?>
    public fun observeTrash(): Flow<List<DocumentSummary>>
    public suspend fun get(id: String): Document?
    /** A [folderId] that no longer exists puts the document at the root instead. */
    public suspend fun create(id: String, title: String, at: Instant, folderId: String? = null): Document
    public suspend fun rename(id: String, title: String, at: Instant)
    public suspend fun setCover(id: String, pageId: String?)
    public suspend fun touch(id: String, at: Instant)
    public suspend fun softDelete(ids: List<String>, at: Instant)
    public suspend fun restore(ids: List<String>)
    /** Removes rows only; file removal is orchestrated by the delete use case (tombstone protocol). */
    public suspend fun deleteRows(id: String)
    public suspend fun idsDeletedBefore(cutoff: Instant): List<String>
    public suspend fun allIds(): Set<String>
    public suspend fun moveToFolder(ids: List<String>, folderId: String?, at: Instant)
    /** Live (not trashed) documents directly inside any of [folderIds]. */
    public suspend fun liveIdsIn(folderIds: Collection<String>): List<String>
}

/** Thin unit of work: lets use cases update several tables atomically without importing Room. */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> T): T
}

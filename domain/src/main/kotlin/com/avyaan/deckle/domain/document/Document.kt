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
    /** NV-03: shown above everything else at its library level (schema v4). */
    val pinned: Boolean = false,
    /** NV-32: when the paper document expires (start of that day), null = no expiry (schema v4). */
    val expiresAt: Instant? = null,
    /** NV-32: the last reminder stage already sent, in days before expiry (see [com.avyaan.deckle.domain.reminder.ExpiryPolicy]). */
    val expiryNotifiedDays: Int? = null,
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
    /** At least one page has recognised, non-blank text (NV-04 filter). */
    val hasText: Boolean = false,
    val pinned: Boolean = false,
    val expiresAt: Instant? = null,
    /** NV-30: ids of the document's tags. */
    val tagIds: Set<String> = emptySet(),
    /** NV-40: at least one page carries a placed signature. */
    val signed: Boolean = false,
)

/** A text excerpt with the ranges that matched the query. */
public data class Snippet(val text: String, val highlights: List<IntRange>)

/** FR-2; PAGES_DESC added by NV-04 (largest first; ties newest first). */
public enum class SortOrder { CREATED_DESC, MODIFIED_DESC, TITLE_ASC, PAGES_DESC }

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

    /** NV-31: every live document in every folder, newest first (smart collections filter this). */
    public fun observeAllLive(): Flow<List<DocumentSummary>>
    public suspend fun setPinned(ids: List<String>, pinned: Boolean)
    /** NV-32: [notifiedDays] is the reminder stage already covered (so setting a date doesn't fire at once). */
    public suspend fun setExpiry(id: String, expiresAt: Instant?, notifiedDays: Int?)
    public suspend fun setExpiryNotified(id: String, days: Int)
    /** Live documents that have an expiry date. */
    public suspend fun withExpiry(): List<Document>
}

/** Thin unit of work: lets use cases update several tables atomically without importing Room. */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> T): T
}

package com.avyaan.deckle.domain.page

import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.ocr.OcrState
import kotlinx.coroutines.flow.Flow

/**
 * One page. Images are files; the database holds paths and normalised geometry only.
 *
 * - [sourcePath]: the upright capture (null when "save originals" is off).
 * - [basePath]: rotated + dewarped, no enhancement. Input to every filter and to OCR.
 * - [processedPath]: what the user sees and exports.
 * - [corners]: crop quad against the *unrotated* source; [rotation] is applied before dewarping.
 * - [name]: optional user-given name ("Front of ID"); null shows as "Page N" and exports as "<title> - NN".
 */
public data class Page(
    val id: String,
    val documentId: String,
    val orderIndex: Long,
    val sourcePath: String?,
    val basePath: String,
    val processedPath: String,
    val thumbPath: String,
    val corners: Quad,
    val rotation: Rotation,
    val mode: EnhancementMode,
    val adjustments: Adjustments,
    val widthPx: Int,
    val heightPx: Int,
    val ocr: OcrState,
    val name: String? = null,
)

public enum class PageFileKind(public val folder: String) {
    SOURCE("source"), BASE("base"), PROCESSED("pages"), THUMB("thumbs")
}

public interface PageRepository {
    public fun observePages(documentId: String): Flow<List<Page>>
    public suspend fun pages(documentId: String): List<Page>
    public suspend fun get(pageId: String): Page?
    public suspend fun insert(pages: List<Page>)
    public suspend fun update(page: Page)
    /** Null clears the name. */
    public suspend fun rename(pageId: String, name: String?)
    public suspend fun updateOrder(pageId: String, orderIndex: Long)
    public suspend fun updateOrders(orders: Map<String, Long>)
    public suspend fun moveToDocument(pageIds: List<String>, documentId: String)
    public suspend fun delete(pageId: String)
    public suspend fun setOcr(pageId: String, state: OcrState, plainText: String?)
    public suspend fun pagesWithOcrStatus(statuses: Set<com.avyaan.deckle.domain.ocr.OcrStatus>): List<Page>
    /** FR-45: pages in order, separated by a blank line. */
    public suspend fun documentText(documentId: String): DocumentText
    /** Every file path any page row points at; used by the janitor to find orphans. */
    public suspend fun allFilePaths(): Set<String>
}

public data class DocumentText(val text: String, val pagesPending: Int)

/** Owns every path on disk. Paths are absolute strings so the domain never sees `java.io.File` semantics. */
public interface PageFileStore {
    public fun pathFor(documentId: String, pageId: String, kind: PageFileKind, revision: String? = null): String
    public fun stagingDir(sessionId: String): String
    public fun stagingPath(sessionId: String, captureId: String, kind: String): String
    public fun cachePath(relative: String): String
    public suspend fun writeBytes(path: String, bytes: ByteArray)
    public suspend fun move(from: String, to: String)
    public suspend fun copy(from: String, to: String)
    public suspend fun delete(path: String)
    public suspend fun deleteRecursively(dir: String)
    public suspend fun exists(path: String): Boolean
    public suspend fun sizeOf(path: String): Long

    /** Deletion protocol, spec 02 §4.3. */
    public suspend fun tombstoneDocument(documentId: String): Tombstone?
    public suspend fun commitTombstone(tombstone: Tombstone)
    public suspend fun revertTombstone(tombstone: Tombstone)
}

public data class Tombstone(val documentId: String, val path: String)

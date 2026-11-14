package com.avyaan.deckle.domain.fakes

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.document.Document
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.DocumentSummary
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.document.TransactionRunner
import com.avyaan.deckle.domain.imaging.ThumbnailMaker
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.DocumentText
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageFileKind
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.page.Tombstone
import com.avyaan.deckle.domain.page.allPaths
import com.avyaan.deckle.domain.scan.ScanSession
import com.avyaan.deckle.domain.scan.ScanSessionStore
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.domain.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeClock(var instant: Instant = Instant.parse("2026-11-11T10:00:00Z")) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun newId(): String = "$prefix-${++n}"
}

object NoopLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Throwable?) = Unit
}

/** Runs the block directly; set [failNext] to simulate a database failure inside the transaction. */
class FakeTransactionRunner : TransactionRunner {
    var failNext = false
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        if (failNext) { failNext = false; error("simulated DB failure") }
        return block()
    }
}

/** An in-memory file system: a set of existing paths. */
class FakeFileStore : PageFileStore {
    val files = linkedMapOf<String, Long>()

    fun put(path: String, size: Long = 100) { files[path] = size }

    override fun pathFor(documentId: String, pageId: String, kind: PageFileKind, revision: String?) =
        "/files/docs/$documentId/${kind.folder}/$pageId${revision?.let { "-$it" } ?: ""}.jpg"
    override fun stagingDir(sessionId: String) = "/files/staging/$sessionId"
    override fun stagingPath(sessionId: String, captureId: String, kind: String) = "/files/staging/$sessionId/$captureId.$kind.jpg"
    override fun cachePath(relative: String) = "/cache/$relative"
    override suspend fun writeBytes(path: String, bytes: ByteArray) { files[path] = bytes.size.toLong() }
    override suspend fun move(from: String, to: String) {
        val size = files.remove(from) ?: error("move: missing $from")
        files[to] = size
    }
    override suspend fun copy(from: String, to: String) { files[to] = files[from] ?: error("copy: missing $from") }
    override suspend fun delete(path: String) { files.remove(path) }
    override suspend fun deleteRecursively(dir: String) { files.keys.removeAll { it.startsWith("$dir/") } }
    override suspend fun exists(path: String) = path in files
    override suspend fun sizeOf(path: String) = files[path] ?: 0L

    override suspend fun tombstoneDocument(documentId: String): Tombstone? {
        val dir = "/files/docs/$documentId/"
        val stone = "/files/.$documentId.deleting/"
        val moved = files.keys.filter { it.startsWith(dir) }
        if (moved.isEmpty()) return null
        moved.forEach { files[stone + it.removePrefix(dir)] = files.remove(it)!! }
        return Tombstone(documentId, stone)
    }
    override suspend fun commitTombstone(tombstone: Tombstone) { files.keys.removeAll { it.startsWith(tombstone.path) } }
    override suspend fun revertTombstone(tombstone: Tombstone) {
        val dir = "/files/docs/${tombstone.documentId}/"
        files.keys.filter { it.startsWith(tombstone.path) }.forEach { files[dir + it.removePrefix(tombstone.path)] = files.remove(it)!! }
    }
}

class FakeDocumentRepository(private val pages: FakePageRepository) : DocumentRepository {
    val docs = MutableStateFlow<Map<String, Document>>(emptyMap())

    /** Tag ids per document, as the Room join table would report them. */
    val tagsOf = mutableMapOf<String, Set<String>>()
    /** Bumped when something outside [docs] (tags) changes, so observers re-query like Room's invalidation. */
    val tick = MutableStateFlow(0)

    private fun summary(d: Document) = DocumentSummary(
        d.id, d.title, pages.all().count { it.documentId == d.id }, d.createdAt, d.modifiedAt, d.deletedAt, null,
        hasText = pages.all().any { it.documentId == d.id && !pages.plainText(it.id).isNullOrBlank() },
        pinned = d.pinned, expiresAt = d.expiresAt, tagIds = tagsOf[d.id].orEmpty(),
        signed = pages.all().any { it.documentId == d.id && it.markup?.signed == true },
    )

    override fun observeLibrary(sort: SortOrder, folderId: String?): Flow<List<DocumentSummary>> =
        combine(docs, tick) { m, _ -> m.values.filter { it.deletedAt == null && it.folderId == folderId }.sortedByDescending { it.createdAt }.map(::summary) }
    override fun search(titleQuery: String, ftsQuery: String?, sort: SortOrder) =
        combine(docs, tick) { m, _ -> m.values.filter { it.deletedAt == null && it.title.contains(titleQuery, true) }.map(::summary) }
    override fun observeDocument(id: String) = docs.map { it[id] }
    override fun observeTrash() = combine(docs, tick) { m, _ -> m.values.filter { it.deletedAt != null }.map(::summary) }
    override suspend fun get(id: String) = docs.value[id]
    override suspend fun create(id: String, title: String, at: Instant, folderId: String?): Document =
        Document(id, title, at, at, null, null, folderId).also { docs.value += id to it }
    override suspend fun rename(id: String, title: String, at: Instant) = edit(id) { it.copy(title = title, modifiedAt = at) }
    override suspend fun setCover(id: String, pageId: String?) = edit(id) { it.copy(coverPageId = pageId) }
    override suspend fun touch(id: String, at: Instant) = edit(id) { it.copy(modifiedAt = at) }
    override suspend fun softDelete(ids: List<String>, at: Instant) = ids.forEach { id -> edit(id) { it.copy(deletedAt = at) } }
    override suspend fun restore(ids: List<String>) = ids.forEach { id -> edit(id) { it.copy(deletedAt = null) } }
    override suspend fun deleteRows(id: String) {
        docs.value -= id
        pages.removeDocument(id)   // ON DELETE CASCADE
    }
    override suspend fun idsDeletedBefore(cutoff: Instant) = docs.value.values.filter { it.deletedAt?.isBefore(cutoff) == true }.map { it.id }
    override suspend fun allIds() = docs.value.keys
    override suspend fun moveToFolder(ids: List<String>, folderId: String?, at: Instant) =
        ids.forEach { id -> edit(id) { it.copy(folderId = folderId) } }
    override suspend fun liveIdsIn(folderIds: Collection<String>) =
        docs.value.values.filter { it.deletedAt == null && it.folderId in folderIds }.map { it.id }

    override fun observeAllLive() = combine(docs, tick) { m, _ -> m.values.filter { it.deletedAt == null }.sortedByDescending { it.createdAt }.map(::summary) }
    override suspend fun setPinned(ids: List<String>, pinned: Boolean) = ids.forEach { id -> edit(id) { it.copy(pinned = pinned) } }
    override suspend fun setExpiry(id: String, expiresAt: Instant?, notifiedDays: Int?) =
        edit(id) { it.copy(expiresAt = expiresAt, expiryNotifiedDays = notifiedDays) }
    override suspend fun setExpiryNotified(id: String, days: Int) = edit(id) { it.copy(expiryNotifiedDays = days) }
    override suspend fun withExpiry() = docs.value.values.filter { it.deletedAt == null && it.expiresAt != null }

    /** What the Room repository does when folder rows go away. */
    fun detachFrom(folderIds: Collection<String>) =
        docs.value.values.filter { it.folderId in folderIds }.forEach { d -> edit(d.id) { it.copy(folderId = null) } }

    private fun edit(id: String, f: (Document) -> Document) {
        docs.value[id]?.let { docs.value += id to f(it) }
    }
}

class FakePageRepository : PageRepository {
    private val pages = MutableStateFlow<Map<String, Page>>(emptyMap())
    private val plain = mutableMapOf<String, String?>()

    fun all(): List<Page> = pages.value.values.toList()
    fun plainText(pageId: String) = plain[pageId]
    fun removeDocument(documentId: String) { pages.value = pages.value.filterValues { it.documentId != documentId } }

    override fun observePages(documentId: String) = pages.map { m -> m.values.filter { it.documentId == documentId }.sortedBy { it.orderIndex } }
    override suspend fun pages(documentId: String) = all().filter { it.documentId == documentId }.sortedBy { it.orderIndex }
    override suspend fun get(pageId: String) = pages.value[pageId]
    override suspend fun insert(pages: List<Page>) { this.pages.value += pages.associateBy { it.id } }
    override suspend fun update(page: Page) { pages.value += page.id to page }
    override suspend fun rename(pageId: String, name: String?) { get(pageId)?.let { update(it.copy(name = name)) } }
    override suspend fun setMarkup(pageId: String, markup: com.avyaan.deckle.domain.markup.PageMarkup?) { get(pageId)?.let { update(it.copy(markup = markup)) } }
    override suspend fun updateOrder(pageId: String, orderIndex: Long) { get(pageId)?.let { update(it.copy(orderIndex = orderIndex)) } }
    override suspend fun updateOrders(orders: Map<String, Long>) = orders.forEach { (id, i) -> updateOrder(id, i) }
    override suspend fun moveToDocument(pageIds: List<String>, documentId: String) =
        pageIds.forEach { id -> get(id)?.let { update(it.copy(documentId = documentId)) } }
    override suspend fun delete(pageId: String) { pages.value -= pageId }
    override suspend fun setOcr(pageId: String, state: OcrState, plainText: String?) {
        get(pageId)?.let { update(it.copy(ocr = state)) }
        plain[pageId] = plainText
    }
    override suspend fun pagesWithOcrStatus(statuses: Set<OcrStatus>) = all().filter { it.ocr.status in statuses }
    override suspend fun documentText(documentId: String) = DocumentText(
        pages(documentId).mapNotNull { plain[it.id] }.joinToString("\n\n"),
        pages(documentId).count { it.ocr.status != OcrStatus.DONE },
    )
    override suspend fun allFilePaths() = all().flatMap { it.allPaths() }.toSet()
}

class FakeScanSessionStore : ScanSessionStore {
    private val sessions = MutableStateFlow<Map<String, ScanSession>>(emptyMap())
    val discarded = mutableListOf<String>()

    override fun observe(sessionId: String) = sessions.map { it[sessionId] }
    override suspend fun get(sessionId: String) = sessions.value[sessionId]
    override suspend fun create(id: String, createdAtMs: Long, targetDocumentId: String?, insertAfterPageId: String?) =
        ScanSession(id, createdAtMs, targetDocumentId, insertAfterPageId).also { sessions.value += id to it }
    override suspend fun update(sessionId: String, transform: (ScanSession) -> ScanSession): ScanSession? {
        val s = sessions.value[sessionId] ?: return null
        return transform(s).also { sessions.value += sessionId to it }
    }
    override suspend fun discard(sessionId: String) { sessions.value -= sessionId; discarded += sessionId }
    override suspend fun listAll() = sessions.value.values.toList()
}

class FakeOcrScheduler : OcrScheduler {
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override fun enqueue(pageIds: List<String>) { enqueued += pageIds }
    override fun cancel(pageId: String) { cancelled += pageId }
}

class FakeThumbnailMaker(private val files: FakeFileStore) : ThumbnailMaker {
    override suspend fun make(imagePath: String, out: String, longEdge: Int): Outcome<Unit> {
        files.put(out, 10); return Outcome.Success(Unit)
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeExportHistory : com.avyaan.deckle.domain.export.ExportHistoryRepository {
    val records = MutableStateFlow<List<com.avyaan.deckle.domain.export.ExportRecord>>(emptyList())
    override fun observe() = records
    override suspend fun add(record: com.avyaan.deckle.domain.export.ExportRecord) {
        records.value = (listOf(record) + records.value).sortedByDescending { it.createdAt }
            .take(com.avyaan.deckle.domain.export.ExportHistoryRepository.MAX_RECORDS)
    }
    override suspend fun delete(id: String) { records.value = records.value.filterNot { it.id == id } }
    override suspend fun clear() { records.value = emptyList() }
}

class FakeFolderRepository(private val docs: FakeDocumentRepository) : com.avyaan.deckle.domain.folder.FolderRepository {
    val folders = MutableStateFlow<Map<String, com.avyaan.deckle.domain.folder.Folder>>(emptyMap())
    override fun observeAll() = folders.map { it.values.toList() }
    override suspend fun all() = folders.value.values.toList()
    override fun observeDocumentCounts() = docs.docs.map { m ->
        m.values.filter { it.deletedAt == null && it.folderId != null }.groupingBy { it.folderId!! }.eachCount()
    }
    override suspend fun get(id: String) = folders.value[id]
    override suspend fun create(folder: com.avyaan.deckle.domain.folder.Folder) { folders.value += folder.id to folder }
    override suspend fun rename(id: String, name: String, at: Instant) {
        folders.value[id]?.let { folders.value += id to it.copy(name = name, modifiedAt = at) }
    }
    override suspend fun move(ids: List<String>, parentId: String?, at: Instant) = ids.forEach { id ->
        folders.value[id]?.let { folders.value += id to it.copy(parentId = parentId, modifiedAt = at) }
    }
    override suspend fun delete(ids: List<String>) {
        folders.value -= ids.toSet()
        docs.detachFrom(ids)
    }
    override suspend fun setPinned(ids: List<String>, pinned: Boolean) = ids.forEach { id ->
        folders.value[id]?.let { folders.value += id to it.copy(pinned = pinned) }
    }
}

class FakeTagRepository(private val docs: FakeDocumentRepository) : com.avyaan.deckle.domain.tag.TagRepository {
    val tags = MutableStateFlow<Map<String, com.avyaan.deckle.domain.tag.Tag>>(emptyMap())
    override fun observeAll() = tags.map { m -> m.values.sortedBy { it.name.lowercase() } }
    override suspend fun all() = tags.value.values.sortedBy { it.name.lowercase() }
    override suspend fun create(tag: com.avyaan.deckle.domain.tag.Tag) { tags.value += tag.id to tag }
    override suspend fun update(id: String, name: String, color: com.avyaan.deckle.domain.tag.TagColor) {
        tags.value[id]?.let { tags.value += id to it.copy(name = name, color = color) }
    }
    override suspend fun delete(id: String) {
        tags.value -= id
        docs.tagsOf.replaceAll { _, set -> set - id }
        docs.tick.value++
    }
    override fun observeTagsOf(documentId: String) = docs.tick.map { docs.tagsOf[documentId].orEmpty() }
    override suspend fun setTags(documentId: String, tagIds: Set<String>) {
        docs.tagsOf[documentId] = tagIds
        docs.tick.value++
    }
    override suspend fun addTag(documentIds: List<String>, tagId: String) {
        documentIds.forEach { docs.tagsOf[it] = docs.tagsOf[it].orEmpty() + tagId }
        docs.tick.value++
    }
}

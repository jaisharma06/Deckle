package com.avyaan.deckle.domain.folder

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** A library folder (schema v3). `parentId == null` means it sits at the library root. */
public data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Instant,
    val modifiedAt: Instant,
)

/** A folder as the library lists it: what it directly contains. */
public data class FolderSummary(
    val folder: Folder,
    val documentCount: Int,
    val folderCount: Int,
)

public interface FolderRepository {
    /** Every folder, for tree work (paths, descendants, the move picker). Folders are few; this stays cheap. */
    public fun observeAll(): Flow<List<Folder>>
    public suspend fun all(): List<Folder>
    /** Live (not trashed) documents per folder id. */
    public fun observeDocumentCounts(): Flow<Map<String, Int>>
    public suspend fun get(id: String): Folder?
    public suspend fun create(folder: Folder)
    public suspend fun rename(id: String, name: String, at: Instant)
    public suspend fun move(ids: List<String>, parentId: String?, at: Instant)
    /**
     * Removes folder rows only. Any document still pointing at one of them (including trashed ones) is
     * moved to the library root, so nothing can end up inside a folder that no longer exists.
     */
    public suspend fun delete(ids: List<String>)
}

/** Pure helpers over the folder forest. */
public object FolderTree {
    /** Root-first chain of folders from the library root down to [folderId] (inclusive). */
    public fun path(folderId: String?, all: Collection<Folder>): List<Folder> {
        val byId = all.associateBy { it.id }
        val chain = ArrayList<Folder>()
        var current = folderId?.let(byId::get)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            chain += current
            current = current.parentId?.let(byId::get)
        }
        return chain.asReversed()
    }

    /** [roots] plus every folder below them. */
    public fun subtree(roots: Collection<String>, all: Collection<Folder>): Set<String> {
        val children = all.groupBy { it.parentId }
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (result.add(id)) children[id]?.forEach { queue += it.id }
        }
        return result
    }

    /** A folder can't move into itself or anywhere below itself. */
    public fun canMove(folderIds: Collection<String>, target: String?, all: Collection<Folder>): Boolean =
        target == null || target !in subtree(folderIds, all)

    /** "Name", "Name (2)", … — the first that no sibling uses (case-insensitive). */
    public fun uniqueName(name: String, siblings: Collection<String>): String {
        val taken = siblings.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name ($n)".lowercase() in taken) n++
        return "$name ($n)"
    }
}

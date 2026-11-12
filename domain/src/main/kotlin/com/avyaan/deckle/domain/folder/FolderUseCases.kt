package com.avyaan.deckle.domain.folder

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TitleRules
import com.avyaan.deckle.domain.document.TransactionRunner
import com.avyaan.deckle.domain.export.ExportArchive
import javax.inject.Inject

/** Creates a folder inside [parentId]; a name already used next to it gets a " (2)" suffix. Null if blank. */
public class CreateFolderUseCase @Inject constructor(
    private val folders: FolderRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(parentId: String?, rawName: String): String? {
        val name = TitleRules.clean(rawName) ?: return null
        val all = folders.all()
        val parent = parentId?.takeIf { id -> all.any { it.id == id } }
        val unique = FolderTree.uniqueName(name, all.filter { it.parentId == parent }.map { it.name })
        val now = clock.now()
        val folder = Folder(ids.newId(), unique, parent, now, now)
        folders.create(folder)
        return folder.id
    }
}

/** False when the name is blank. Renaming to a sibling's name gets the same suffix rule as creating. */
public class RenameFolderUseCase @Inject constructor(
    private val folders: FolderRepository,
    private val clock: Clock,
) {
    public suspend operator fun invoke(id: String, rawName: String): Boolean {
        val name = TitleRules.clean(rawName) ?: return false
        val all = folders.all()
        val folder = all.firstOrNull { it.id == id } ?: return false
        if (folder.name == name) return true
        val siblings = all.filter { it.parentId == folder.parentId && it.id != id }.map { it.name }
        folders.rename(id, FolderTree.uniqueName(name, siblings), clock.now())
        return true
    }
}

/**
 * Moves documents and folders into [target] (null = the library root). Refused (false) when a folder
 * would move into itself or one of its own sub-folders, or the target no longer exists.
 */
public class MoveToFolderUseCase @Inject constructor(
    private val folders: FolderRepository,
    private val documents: DocumentRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    public suspend operator fun invoke(documentIds: List<String>, folderIds: List<String>, target: String?): Boolean {
        val all = folders.all()
        if (target != null && all.none { it.id == target }) return false
        if (!FolderTree.canMove(folderIds, target, all)) return false
        val now = clock.now()
        tx.inTransaction {
            if (documentIds.isNotEmpty()) documents.moveToFolder(documentIds, target, now)
            if (folderIds.isNotEmpty()) folders.move(folderIds, target, now)
        }
        return true
    }
}

/**
 * Deletes folders. With [keepContents] their documents and sub-folders move up into the deleted folder's
 * parent; otherwise every document anywhere below goes to Trash (restorable for 30 days, back at the
 * library root) and the whole sub-tree is removed.
 */
public class DeleteFoldersUseCase @Inject constructor(
    private val folders: FolderRepository,
    private val documents: DocumentRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    public suspend operator fun invoke(folderIds: List<String>, keepContents: Boolean) {
        val all = folders.all()
        val now = clock.now()
        tx.inTransaction {
            if (keepContents) {
                // Deepest first, so a deleted folder inside another deleted folder hands its contents to a
                // parent that is itself about to hand them further up.
                val doomed = folderIds.toSet()
                val byId = all.associateBy { it.id }
                fun survivingParent(id: String): String? {
                    var p = byId[id]?.parentId
                    while (p != null && p in doomed) p = byId[p]?.parentId
                    return p
                }
                folderIds.forEach { id ->
                    val parent = survivingParent(id)
                    val childFolders = all.filter { it.parentId == id && it.id !in doomed }.map { it.id }
                    if (childFolders.isNotEmpty()) folders.move(childFolders, parent, now)
                    val docs = documents.liveIdsIn(listOf(id))
                    if (docs.isNotEmpty()) documents.moveToFolder(docs, parent, now)
                }
                folders.delete(folderIds)
            } else {
                val subtree = FolderTree.subtree(folderIds, all).toList()
                val docs = documents.liveIdsIn(subtree)
                if (docs.isNotEmpty()) documents.softDelete(docs, now)
                folders.delete(subtree)
            }
        }
    }
}

/**
 * What a folder export contains: every live document below the folder, each placed in the ZIP under its
 * sub-folder path ("Taxes/2025/…"), so the archive mirrors the library.
 */
public class BuildFolderExportUseCase @Inject constructor(
    private val folders: FolderRepository,
    private val documents: DocumentRepository,
) {
    public data class FolderExport(val documentIds: List<String>, val archive: ExportArchive)

    /** Null when the folder is gone or holds no documents. */
    public suspend operator fun invoke(folderId: String): FolderExport? {
        val all = folders.all()
        val root = all.firstOrNull { it.id == folderId } ?: return null
        val byId = all.associateBy { it.id }
        val ids = ArrayList<String>()
        val dirs = LinkedHashMap<String, String>()
        FolderTree.subtree(listOf(folderId), all).forEach { fid ->
            // Path relative to the exported folder; its own documents sit at the top of the ZIP.
            val segments = ArrayList<String>()
            var cur = byId[fid]
            while (cur != null && cur.id != folderId) {
                segments += TitleRules.fileName(cur.name)
                cur = cur.parentId?.let(byId::get)
            }
            val dir = segments.asReversed().joinToString("/")
            documents.liveIdsIn(listOf(fid)).forEach { docId ->
                ids += docId
                if (dir.isNotEmpty()) dirs[docId] = dir
            }
        }
        if (ids.isEmpty()) return null
        return FolderExport(ids, ExportArchive(TitleRules.fileName(root.name), dirs))
    }
}

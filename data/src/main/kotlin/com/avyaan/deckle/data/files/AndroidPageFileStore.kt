package com.avyaan.deckle.data.files

import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.page.PageFileKind
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.Tombstone
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.withContext

public class AndroidPageFileStore @Inject constructor(
    private val layout: FileLayout,
    private val dispatchers: DispatcherProvider,
) : PageFileStore {

    override fun pathFor(documentId: String, pageId: String, kind: PageFileKind, revision: String?): String =
        layout.pageFile(documentId, pageId, kind, revision).absolutePath

    override fun stagingDir(sessionId: String): String = layout.sessionDir(sessionId).absolutePath
    override fun stagingPath(sessionId: String, captureId: String, kind: String): String =
        layout.stagingFile(sessionId, captureId, kind).absolutePath
    override fun cachePath(relative: String): String = layout.cacheFile(relative).absolutePath

    override suspend fun writeBytes(path: String, bytes: ByteArray): Unit = io { AtomicFiles.write(File(path)) { it.write(bytes) } }

    override suspend fun move(from: String, to: String): Unit = io { AtomicFiles.move(File(from), File(to)) }

    override suspend fun copy(from: String, to: String): Unit = io {
        AtomicFiles.write(File(to)) { out -> File(from).inputStream().use { it.copyTo(out) } }
    }

    override suspend fun delete(path: String): Unit = io { File(path).delete() }
    override suspend fun deleteRecursively(dir: String): Unit = io { File(dir).deleteRecursively() }
    override suspend fun exists(path: String): Boolean = io { File(path).exists() }
    override suspend fun sizeOf(path: String): Long = io { File(path).length() }

    override suspend fun tombstoneDocument(documentId: String): Tombstone? = io {
        val dir = layout.documentDir(documentId)
        if (!dir.exists()) return@io null
        val stone = layout.tombstone(documentId)
        stone.deleteRecursively()
        if (!dir.renameTo(stone)) throw IOException("Could not tombstone document folder")
        Tombstone(documentId, stone.absolutePath)
    }

    override suspend fun commitTombstone(tombstone: Tombstone): Unit = io { File(tombstone.path).deleteRecursively() }

    override suspend fun revertTombstone(tombstone: Tombstone): Unit = io {
        File(tombstone.path).renameTo(layout.documentDir(tombstone.documentId))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatchers.io) { block() }
}

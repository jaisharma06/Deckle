package com.avyaan.deckle.data.files

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.storage.FileJanitor
import com.avyaan.deckle.domain.storage.StorageInspector
import com.avyaan.deckle.domain.storage.StorageUsage
import java.io.File
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Launch-time file cleanup (spec 02 §6). Resolves half-finished deletes, removes temp files,
 * page files no row points at, folders of documents that no longer exist, and stale exports.
 */
public class AndroidFileJanitor @Inject constructor(
    private val layout: FileLayout,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val logger: Logger,
) : FileJanitor {

    override suspend fun clean(liveDocumentIds: Set<String>, referencedPaths: Set<String>, liveSessionIds: Set<String>): Unit =
        withContext(dispatchers.io) {
            resolveTombstones(liveDocumentIds)
            layout.docsRoot.listFiles()?.forEach { docDir ->
                if (docDir.name !in liveDocumentIds) {
                    docDir.deleteRecursively()
                } else {
                    docDir.walkBottomUp().filter { it.isFile && it.absolutePath !in referencedPaths }.forEach {
                        logger.debug(TAG, "Removing orphan ${it.name}")
                        it.delete()
                    }
                }
            }
            layout.stagingRoot.listFiles()?.forEach { dir ->
                if (dir.name !in liveSessionIds) dir.deleteRecursively()
                else dir.listFiles()?.filter { it.name.endsWith(AtomicFiles.TMP_SUFFIX) }?.forEach(File::delete)
            }
            val cutoff = clock.now().minus(EXPORT_MAX_AGE).toEpochMilli()
            layout.exportsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }

    /** Crash between "rename to tombstone" and "delete rows": the row still exists, so rename back. */
    private fun resolveTombstones(liveDocumentIds: Set<String>) {
        layout.filesRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".") && it.name.endsWith(FileLayout.TOMBSTONE_SUFFIX) }
            ?.forEach { stone ->
                val id = stone.name.removePrefix(".").removeSuffix(FileLayout.TOMBSTONE_SUFFIX)
                if (id in liveDocumentIds) stone.renameTo(layout.documentDir(id)) else stone.deleteRecursively()
            }
    }

    private companion object {
        const val TAG = "Janitor"
        val EXPORT_MAX_AGE: Duration = Duration.ofHours(24)
    }
}

/** FR-73: storage figures and "Clear cache" (derived files only — never pages). */
public class AndroidStorageInspector @Inject constructor(
    private val layout: FileLayout,
    private val dispatchers: DispatcherProvider,
) : StorageInspector {

    override suspend fun usage(): StorageUsage = withContext(dispatchers.io) {
        StorageUsage(documentsBytes = layout.docsRoot.sizeRecursive(), cacheBytes = layout.cacheRoot.sizeRecursive())
    }

    override suspend fun clearCache(): Unit = withContext(dispatchers.io) {
        layout.cacheRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun File.sizeRecursive(): Long = if (!exists()) 0 else walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

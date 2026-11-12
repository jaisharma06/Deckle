package com.avyaan.deckle.domain.storage

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.PurgeTrashUseCase
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.scan.ScanSessionStore
import java.time.Duration
import javax.inject.Inject

/** FR-73. */
public data class StorageUsage(val documentsBytes: Long, val cacheBytes: Long)

public interface StorageInspector {
    public suspend fun usage(): StorageUsage
    /** Removes derived, regenerable files only (exports, previews, image cache). Never pages (FR-73). */
    public suspend fun clearCache()
}

/** File-level cleanup that only the data layer can do (spec 02 §6 step 4). */
public interface FileJanitor {
    public suspend fun clean(liveDocumentIds: Set<String>, referencedPaths: Set<String>, liveSessionIds: Set<String>)
}

public class GetStorageUsageUseCase @Inject constructor(private val inspector: StorageInspector) {
    public suspend operator fun invoke(): StorageUsage = inspector.usage()
}

public class ClearCacheUseCase @Inject constructor(private val inspector: StorageInspector) {
    public suspend operator fun invoke(): Unit = inspector.clearCache()
}

/**
 * Launch-time housekeeping, run in the background after startup (spec 02 §6):
 * purge old trash, retry failed OCR once, re-queue stranded OCR, clean orphaned files and old sessions.
 */
public class RunStartupMaintenanceUseCase @Inject constructor(
    private val purgeTrash: PurgeTrashUseCase,
    private val documents: DocumentRepository,
    private val pages: PageRepository,
    private val sessions: ScanSessionStore,
    private val ocr: OcrScheduler,
    private val janitor: FileJanitor,
    private val clock: Clock,
    private val logger: Logger,
) {
    public suspend operator fun invoke() {
        step("purge trash") { purgeTrash() }
        step("ocr retry") { requeueOcr() }
        step("sessions") { discardStaleSessions() }
        step("janitor") {
            janitor.clean(documents.allIds(), pages.allFilePaths(), sessions.listAll().map { it.id }.toSet())
        }
    }

    private suspend fun requeueOcr() {
        val candidates = pages.pagesWithOcrStatus(setOf(OcrStatus.QUEUED, OcrStatus.RUNNING, OcrStatus.FAILED))
        val toRun = candidates.filter { it.ocr.status != OcrStatus.FAILED || it.ocr.attempts < OcrState.MAX_ATTEMPTS }
        toRun.filter { it.ocr.status != OcrStatus.QUEUED }.forEach {
            pages.setOcr(it.id, it.ocr.copy(status = OcrStatus.QUEUED, lines = null), plainText = null)
        }
        ocr.enqueue(toRun.map { it.id })
    }

    private suspend fun discardStaleSessions() {
        val cutoff = clock.now().minus(SESSION_MAX_AGE).toEpochMilli()
        sessions.listAll().filter { it.createdAtMs < cutoff }.forEach { sessions.discard(it.id) }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Maintenance", "Step '$name' failed", e)   // never block startup on housekeeping
        }
    }

    public companion object {
        public val SESSION_MAX_AGE: Duration = Duration.ofHours(24)
    }
}

/** A resumable scan shown as a banner in the library. */
public class FindResumableSessionUseCase @Inject constructor(private val sessions: ScanSessionStore) {
    public suspend operator fun invoke(): com.avyaan.deckle.domain.scan.ScanSession? =
        sessions.listAll().filter { it.captures.isNotEmpty() }.maxByOrNull { it.createdAtMs }
}

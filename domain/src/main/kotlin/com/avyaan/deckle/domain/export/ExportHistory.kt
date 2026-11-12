package com.avyaan.deckle.domain.export

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Where an export went, without the URI details (those are in [ExportRecord.outputs]). */
public enum class ExportDestination { SHARE, FILE, FOLDER }

/**
 * One finished export, for the History screen. [outputs] are what the export produced: file paths in the
 * app's cache for SHARE (they may since have been cleaned up), content URIs for FILE and FOLDER.
 */
public data class ExportRecord(
    val id: String,
    val createdAt: Instant,
    /** Titles of the exported documents, in export order. */
    val titles: List<String>,
    val documentIds: List<String>,
    val pageCount: Int,
    val format: ExportFormat,
    val destination: ExportDestination,
    val outputs: List<String>,
    val mimeType: String,
    val passwordProtected: Boolean,
)

public interface ExportHistoryRepository {
    /** Newest first. */
    public fun observe(): Flow<List<ExportRecord>>
    /** Adds a record and trims the history to [MAX_RECORDS], dropping the oldest. */
    public suspend fun add(record: ExportRecord)
    public suspend fun delete(id: String)
    public suspend fun clear()

    public companion object {
        public const val MAX_RECORDS: Int = 200
    }
}

public fun ExportTarget.destination(): ExportDestination = when (this) {
    ExportTarget.Share -> ExportDestination.SHARE
    is ExportTarget.File -> ExportDestination.FILE
    is ExportTarget.Folder -> ExportDestination.FOLDER
}

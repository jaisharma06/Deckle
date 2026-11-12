package com.avyaan.deckle.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [Index("deletedAt"), Index("modifiedAt"), Index("createdAt"), Index("folderId")],
)
public data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val coverPageId: String?,
    val deletedAt: Long?,
    /**
     * Containing folder (schema v3); null = library root. Deliberately not a foreign key: adding one would
     * mean rebuilding the documents table in the migration. FolderRepository.delete detaches documents.
     */
    @ColumnInfo(defaultValue = "NULL") val folderId: String? = null,
)

/** Library folders (schema v3). `parentId` null = top level. */
@Entity(tableName = "folders", indices = [Index("parentId")])
public data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val modifiedAt: Long,
)

/** Live document count of one folder. */
public data class FolderCountRow(val folderId: String, val count: Int)

/**
 * Columns from the requirements plus `basePath`, `brightness`, `contrast`, `ocrAttempts` (spec 02 §3.2).
 *
 * WARNING: `pages_fts` maps to this table's implicit rowid. Never run VACUUM; if a future migration must,
 * follow it with `INSERT INTO pages_fts(pages_fts) VALUES('rebuild')`.
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("ocrStatus")],
)
public data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val orderIndex: Long,
    val sourcePath: String?,
    val basePath: String,
    val processedPath: String,
    val thumbPath: String,
    val cornersJson: String,
    val enhancementMode: String,
    val rotationDegrees: Int,
    val brightness: Float,
    val contrast: Float,
    val widthPx: Int,
    val heightPx: Int,
    val ocrStatus: String,
    val ocrAttempts: Int,
    val ocrLinesJson: String?,
    val ocrPlainText: String?,
    /** Optional user-given page name (schema v2). */
    @ColumnInfo(defaultValue = "NULL") val name: String? = null,
)

/** FR-3 full-text index over OCR text. Room keeps it in sync with triggers. */
@Fts4(contentEntity = PageEntity::class)
@Entity(tableName = "pages_fts")
public data class PageFtsEntity(
    @ColumnInfo(name = "ocrPlainText") val ocrPlainText: String?,
)

/** Library row: one document plus its cover thumbnail and page count. */
public data class DocumentSummaryRow(
    val id: String,
    val title: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val deletedAt: Long?,
    val pageCount: Int,
    val coverThumbPath: String?,
    val snippet: String?,
    val matchRank: Int,
)

/**
 * Export history (schema v2). Lists are JSON arrays of strings; they are only ever read whole, so a join
 * table would add complexity without a query that needs it.
 */
@Entity(tableName = "export_history", indices = [Index("createdAt")])
public data class ExportRecordEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val titlesJson: String,
    val documentIdsJson: String,
    val pageCount: Int,
    val format: String,
    val destination: String,
    val outputsJson: String,
    val mimeType: String,
    val passwordProtected: Boolean,
)

package com.avyaan.deckle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_COLUMNS = """
    d.id, d.title, d.createdAt, d.modifiedAt, d.deletedAt,
    (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
    COALESCE(
        (SELECT thumbPath FROM pages WHERE id = d.coverPageId),
        (SELECT thumbPath FROM pages WHERE documentId = d.id ORDER BY orderIndex LIMIT 1)
    ) AS coverThumbPath,
    EXISTS(SELECT 1 FROM pages p WHERE p.documentId = d.id AND LENGTH(TRIM(p.ocrPlainText)) > 0) AS hasText
"""

@Dao
public interface DocumentDao {
    // Room can't bind ORDER BY columns, so there is one query per sort order (FR-2).
    @Query("SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d WHERE d.deletedAt IS NULL AND d.folderId IS :folderId ORDER BY d.createdAt DESC")
    public fun libraryByCreated(folderId: String?): Flow<List<DocumentSummaryRow>>

    @Query("SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d WHERE d.deletedAt IS NULL AND d.folderId IS :folderId ORDER BY d.modifiedAt DESC")
    public fun libraryByModified(folderId: String?): Flow<List<DocumentSummaryRow>>

    @Query("SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d WHERE d.deletedAt IS NULL AND d.folderId IS :folderId ORDER BY d.title COLLATE NOCASE ASC")
    public fun libraryByTitle(folderId: String?): Flow<List<DocumentSummaryRow>>

    /** FR-3: title matches (rank 0) then OCR text matches with a marked snippet (rank 1). */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d
        WHERE d.deletedAt IS NULL AND d.title LIKE '%' || :titleLike || '%' ESCAPE '\'
        UNION ALL
        SELECT $SUMMARY_COLUMNS,
               snippet(pages_fts, char(2), char(3), '…', -1, 10) AS snippet, 1 AS matchRank
        FROM pages_fts
        JOIN pages pg ON pg.rowid = pages_fts.rowid
        JOIN documents d ON d.id = pg.documentId
        WHERE d.deletedAt IS NULL AND pages_fts MATCH :ftsQuery
        """,
    )
    public fun search(titleLike: String, ftsQuery: String): Flow<List<DocumentSummaryRow>>

    @Query("SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d WHERE d.deletedAt IS NULL AND d.title LIKE '%' || :titleLike || '%' ESCAPE '\\'")
    public fun searchTitles(titleLike: String): Flow<List<DocumentSummaryRow>>

    @Query("SELECT $SUMMARY_COLUMNS, NULL AS snippet, 0 AS matchRank FROM documents d WHERE d.deletedAt IS NOT NULL ORDER BY d.deletedAt DESC")
    public fun trash(): Flow<List<DocumentSummaryRow>>

    @Query("SELECT * FROM documents WHERE id = :id")
    public fun observe(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    public suspend fun get(id: String): DocumentEntity?

    @Insert
    public suspend fun insert(doc: DocumentEntity)

    @Query("UPDATE documents SET title = :title, modifiedAt = :at WHERE id = :id")
    public suspend fun rename(id: String, title: String, at: Long)

    @Query("UPDATE documents SET coverPageId = :pageId WHERE id = :id")
    public suspend fun setCover(id: String, pageId: String?)

    @Query("UPDATE documents SET modifiedAt = :at WHERE id = :id")
    public suspend fun touch(id: String, at: Long)

    @Query("UPDATE documents SET deletedAt = :at WHERE id IN (:ids)")
    public suspend fun softDelete(ids: List<String>, at: Long)

    @Query("UPDATE documents SET deletedAt = NULL WHERE id IN (:ids)")
    public suspend fun restore(ids: List<String>)

    @Query("DELETE FROM documents WHERE id = :id")
    public suspend fun delete(id: String)

    @Query("SELECT id FROM documents WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    public suspend fun idsDeletedBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM documents")
    public suspend fun allIds(): List<String>

    // Filing a document away is not an edit: modifiedAt stays, so "Last modified" sorting doesn't jump.
    @Query("UPDATE documents SET folderId = :folderId WHERE id IN (:ids)")
    public suspend fun moveToFolder(ids: List<String>, folderId: String?)

    @Query("SELECT id FROM documents WHERE deletedAt IS NULL AND folderId IN (:folderIds)")
    public suspend fun liveIdsIn(folderIds: List<String>): List<String>

    @Query("UPDATE documents SET folderId = NULL WHERE folderId IN (:folderIds)")
    public suspend fun detachFrom(folderIds: List<String>)
}

@Dao
public interface FolderDao {
    @Query("SELECT * FROM folders")
    public fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    public suspend fun all(): List<FolderEntity>

    @Query("SELECT folderId, COUNT(*) AS count FROM documents WHERE deletedAt IS NULL AND folderId IS NOT NULL GROUP BY folderId")
    public fun observeCounts(): Flow<List<FolderCountRow>>

    @Query("SELECT * FROM folders WHERE id = :id")
    public suspend fun get(id: String): FolderEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :id)")
    public suspend fun exists(id: String): Boolean

    @Insert
    public suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, modifiedAt = :at WHERE id = :id")
    public suspend fun rename(id: String, name: String, at: Long)

    @Query("UPDATE folders SET parentId = :parentId, modifiedAt = :at WHERE id IN (:ids)")
    public suspend fun move(ids: List<String>, parentId: String?, at: Long)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    public suspend fun delete(ids: List<String>)
}

@Dao
public interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex")
    public fun observeByDocument(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex")
    public suspend fun byDocument(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    public suspend fun get(id: String): PageEntity?

    @Insert
    public suspend fun insert(pages: List<PageEntity>)

    @Update
    public suspend fun update(page: PageEntity)

    @Query("UPDATE pages SET name = :name WHERE id = :id")
    public suspend fun rename(id: String, name: String?)

    @Query("UPDATE pages SET orderIndex = :orderIndex WHERE id = :id")
    public suspend fun updateOrder(id: String, orderIndex: Long)

    @Query("UPDATE pages SET documentId = :documentId WHERE id IN (:ids)")
    public suspend fun moveToDocument(ids: List<String>, documentId: String)

    @Query("DELETE FROM pages WHERE id = :id")
    public suspend fun delete(id: String)

    @Query("UPDATE pages SET ocrStatus = :status, ocrAttempts = :attempts, ocrLinesJson = :linesJson, ocrPlainText = :plainText WHERE id = :id")
    public suspend fun setOcr(id: String, status: String, attempts: Int, linesJson: String?, plainText: String?)

    @Query("SELECT * FROM pages WHERE ocrStatus IN (:statuses)")
    public suspend fun withOcrStatus(statuses: List<String>): List<PageEntity>

    @Query("SELECT sourcePath, basePath, processedPath, thumbPath FROM pages")
    public suspend fun allPaths(): List<PagePaths>
}

public data class PagePaths(val sourcePath: String?, val basePath: String, val processedPath: String, val thumbPath: String)

@Dao
public interface ExportHistoryDao {
    @Query("SELECT * FROM export_history ORDER BY createdAt DESC")
    public fun observe(): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(record: ExportRecordEntity)

    /** Keeps the newest [keep] records. */
    @Query("DELETE FROM export_history WHERE id NOT IN (SELECT id FROM export_history ORDER BY createdAt DESC LIMIT :keep)")
    public suspend fun trim(keep: Int)

    @Query("DELETE FROM export_history WHERE id = :id")
    public suspend fun delete(id: String)

    @Query("DELETE FROM export_history")
    public suspend fun clear()
}

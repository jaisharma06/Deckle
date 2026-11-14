package com.avyaan.deckle.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.avyaan.deckle.data.db.DeckleDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every migration runs against a database created from the exported schema of the old version, with real
 * rows in it, and Room validates the result against the new version's exported schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeckleDatabase::class.java)

    @Test
    fun `v1 to v2 keeps documents and pages, adds page names and export history`() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO documents (id, title, createdAt, modifiedAt, coverPageId, deletedAt) VALUES ('d', 'Lease', 1, 2, 'p', NULL)")
            execSQL(
                "INSERT INTO pages (id, documentId, orderIndex, sourcePath, basePath, processedPath, thumbPath, cornersJson, " +
                    "enhancementMode, rotationDegrees, brightness, contrast, widthPx, heightPx, ocrStatus, ocrAttempts, ocrLinesJson, ocrPlainText) " +
                    "VALUES ('p', 'd', 1024, NULL, '/b', '/p', '/t', '{}', 'COLOUR', 0, 0.0, 0.0, 100, 140, 'DONE', 1, NULL, 'rent')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, *DeckleDatabase.MIGRATIONS)

        db.query("SELECT d.title, p.ocrPlainText, p.name FROM pages p JOIN documents d ON d.id = p.documentId").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lease")
            assertThat(c.getString(1)).isEqualTo("rent")
            assertThat(c.isNull(2)).isTrue()
        }
        db.execSQL(
            "INSERT INTO export_history (id, createdAt, titlesJson, documentIdsJson, pageCount, format, destination, outputsJson, mimeType, passwordProtected) " +
                "VALUES ('e', 3, '[\"Lease\"]', '[\"d\"]', 1, 'PDF', 'SHARE', '[]', 'application/pdf', 0)",
        )
        db.query("SELECT COUNT(*) FROM export_history").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1) }
    }

    @Test
    fun `v1 to v3 keeps documents at the library root and adds folders`() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO documents (id, title, createdAt, modifiedAt, coverPageId, deletedAt) VALUES ('d', 'Lease', 1, 2, NULL, NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true, *DeckleDatabase.MIGRATIONS)

        db.query("SELECT title, folderId FROM documents").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lease")
            assertThat(c.isNull(1)).isTrue()
        }
        db.execSQL("INSERT INTO folders (id, name, parentId, createdAt, modifiedAt) VALUES ('f', 'Home', NULL, 1, 1)")
        db.execSQL("UPDATE documents SET folderId = 'f'")
        db.query("SELECT COUNT(*) FROM documents WHERE folderId IS 'f'").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1) }
    }

    @Test
    fun `v3 to v4 keeps everything and starts unpinned, untagged, unmarked`() {
        helper.createDatabase(DB, 3).apply {
            execSQL("INSERT INTO folders (id, name, parentId, createdAt, modifiedAt) VALUES ('f', 'Home', NULL, 1, 1)")
            execSQL("INSERT INTO documents (id, title, createdAt, modifiedAt, coverPageId, deletedAt, folderId) VALUES ('d', 'Lease', 1, 2, NULL, NULL, 'f')")
            execSQL(
                "INSERT INTO pages (id, documentId, orderIndex, sourcePath, basePath, processedPath, thumbPath, cornersJson, " +
                    "enhancementMode, rotationDegrees, brightness, contrast, widthPx, heightPx, ocrStatus, ocrAttempts, ocrLinesJson, ocrPlainText, name) " +
                    "VALUES ('p', 'd', 1024, NULL, '/b', '/p', '/t', '{}', 'COLOUR', 0, 0.0, 0.0, 100, 140, 'DONE', 1, NULL, 'rent', 'Front')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, *DeckleDatabase.MIGRATIONS)

        db.query("SELECT d.title, d.folderId, d.pinned, d.expiresAt, p.name, p.markupJson FROM pages p JOIN documents d ON d.id = p.documentId").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Lease")
            assertThat(c.getString(1)).isEqualTo("f")
            assertThat(c.getInt(2)).isEqualTo(0)
            assertThat(c.isNull(3)).isTrue()
            assertThat(c.getString(4)).isEqualTo("Front")
            assertThat(c.isNull(5)).isTrue()
        }
        db.query("SELECT pinned FROM folders").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
        db.execSQL("INSERT INTO tags (id, name, color, createdAt) VALUES ('t', 'Bills', 'AMBER', 1)")
        db.execSQL("INSERT INTO document_tags (documentId, tagId) VALUES ('d', 't')")
        db.query("SELECT COUNT(*) FROM document_tags").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1) }
    }

    /** Absolute: under Robolectric, Room's helper resolves bare names to a path and then compares the two. */
    private val DB: String = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test.db").path
}

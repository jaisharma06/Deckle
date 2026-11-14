package com.avyaan.deckle.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. Each SQL statement must produce exactly the schema Room exports for the new
 * version (`data/schemas/.../<n>.json`); `MigrationTest` checks that against the real v1 schema.
 */
internal object Migrations {

    /** v2: optional page names and the export history table. Additive only, so no data is touched. */
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pages` ADD COLUMN `name` TEXT DEFAULT NULL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `export_history` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`titlesJson` TEXT NOT NULL, `documentIdsJson` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, " +
                    "`format` TEXT NOT NULL, `destination` TEXT NOT NULL, `outputsJson` TEXT NOT NULL, " +
                    "`mimeType` TEXT NOT NULL, `passwordProtected` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_export_history_createdAt` ON `export_history` (`createdAt`)")
        }
    }

    /** v3: folders. Existing documents get `folderId = NULL`, i.e. they stay at the library root. */
    val V2_TO_V3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `folderId` TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_folderId` ON `documents` (`folderId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_parentId` ON `folders` (`parentId`)")
        }
    }

    /**
     * v4 (phases 3 and 4): pins, expiry dates, tags and page markup. Additive only: every existing document
     * starts unpinned, untagged and without an expiry date; every page without marks.
     */
    val V3_TO_V4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `expiresAt` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `expiryNotifiedDays` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `folders` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `pages` ADD COLUMN `markupJson` TEXT DEFAULT NULL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `document_tags` (`documentId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`documentId`, `tagId`), " +
                    "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_tags_tagId` ON `document_tags` (`tagId`)")
        }
    }
}

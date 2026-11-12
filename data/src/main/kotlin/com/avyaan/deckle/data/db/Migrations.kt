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
}

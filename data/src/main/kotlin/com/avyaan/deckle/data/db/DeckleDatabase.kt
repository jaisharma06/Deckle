package com.avyaan.deckle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import com.avyaan.deckle.domain.document.TransactionRunner
import javax.inject.Inject

@Database(
    entities = [
        DocumentEntity::class, PageEntity::class, PageFtsEntity::class, ExportRecordEntity::class, FolderEntity::class,
        TagEntity::class, DocumentTagEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
public abstract class DeckleDatabase : RoomDatabase() {
    public abstract fun documents(): DocumentDao
    public abstract fun pages(): PageDao
    public abstract fun exportHistory(): ExportHistoryDao
    public abstract fun folders(): FolderDao
    public abstract fun tags(): TagDao

    public companion object {
        public const val NAME: String = "deckle.db"

        /**
         * Every schema change needs a written Migration here. Destructive fallback is banned (CI greps for it):
         * for this app it would silently delete the user's only copy of their documents.
         */
        public val MIGRATIONS: Array<Migration> = arrayOf(Migrations.V1_TO_V2, Migrations.V2_TO_V3, Migrations.V3_TO_V4)

        public fun build(context: Context): DeckleDatabase =
            Room.databaseBuilder(context, DeckleDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

public class RoomTransactionRunner @Inject constructor(private val db: DeckleDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}

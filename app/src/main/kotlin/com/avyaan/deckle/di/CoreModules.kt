package com.avyaan.deckle.di

import android.content.Context
import com.avyaan.deckle.data.common.AndroidLogger
import com.avyaan.deckle.data.common.ElapsedRealtimeClock
import com.avyaan.deckle.data.common.SystemClock
import com.avyaan.deckle.data.common.UuidGenerator
import com.avyaan.deckle.data.db.DeckleDatabase
import com.avyaan.deckle.data.db.RoomDocumentRepository
import com.avyaan.deckle.data.db.RoomExportHistoryRepository
import com.avyaan.deckle.data.db.RoomFolderRepository
import com.avyaan.deckle.domain.folder.FolderRepository
import com.avyaan.deckle.data.db.RoomPageRepository
import com.avyaan.deckle.domain.export.ExportHistoryRepository
import com.avyaan.deckle.data.db.RoomTransactionRunner
import com.avyaan.deckle.data.files.AndroidFileJanitor
import com.avyaan.deckle.data.files.AndroidPageFileStore
import com.avyaan.deckle.data.files.AndroidStorageInspector
import com.avyaan.deckle.data.files.JsonScanSessionStore
import com.avyaan.deckle.data.settings.DataStoreSettingsRepository
import com.avyaan.deckle.domain.common.ApplicationScope
import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.common.MonotonicClock
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TransactionRunner
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.scan.ScanSessionStore
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.domain.storage.FileJanitor
import com.avyaan.deckle.domain.storage.StorageInspector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Spec 01 §7: one full-res imaging job at a time; two preview jobs. Injected, never static. */
private class AppDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val imaging: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    override val preview: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
}

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @Singleton
    fun dispatchers(): DispatcherProvider = AppDispatchers()

    @Provides @Singleton @ApplicationScope
    fun applicationScope(dispatchers: DispatcherProvider): CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): DeckleDatabase = DeckleDatabase.build(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindings {
    @Binds abstract fun clock(impl: SystemClock): Clock
    @Binds abstract fun monotonic(impl: ElapsedRealtimeClock): MonotonicClock
    @Binds abstract fun ids(impl: UuidGenerator): IdGenerator
    @Binds abstract fun logger(impl: AndroidLogger): Logger
    @Binds abstract fun transactions(impl: RoomTransactionRunner): TransactionRunner
    @Binds @Singleton abstract fun documents(impl: RoomDocumentRepository): DocumentRepository
    @Binds @Singleton abstract fun pages(impl: RoomPageRepository): PageRepository
    @Binds @Singleton abstract fun exportHistory(impl: RoomExportHistoryRepository): ExportHistoryRepository
    @Binds @Singleton abstract fun folders(impl: RoomFolderRepository): FolderRepository
    @Binds @Singleton abstract fun tags(impl: com.avyaan.deckle.data.db.RoomTagRepository): com.avyaan.deckle.domain.tag.TagRepository
    @Binds abstract fun signatures(impl: com.avyaan.deckle.data.markup.EncryptedSignatureStore): com.avyaan.deckle.domain.markup.SignatureStore
    @Binds abstract fun secretBox(impl: com.avyaan.deckle.data.markup.KeystoreSecretBox): com.avyaan.deckle.data.markup.SecretBox
    @Binds abstract fun reminders(impl: com.avyaan.deckle.data.reminder.AndroidReminderNotifier): com.avyaan.deckle.domain.reminder.ReminderNotifier
    @Binds @Singleton abstract fun files(impl: AndroidPageFileStore): PageFileStore
    @Binds abstract fun sessions(impl: JsonScanSessionStore): ScanSessionStore
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun janitor(impl: AndroidFileJanitor): FileJanitor
    @Binds abstract fun storage(impl: AndroidStorageInspector): StorageInspector
}

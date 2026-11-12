package com.avyaan.deckle.data.export

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.export.ExportHistoryRepository
import com.avyaan.deckle.domain.export.ExportRecord
import com.avyaan.deckle.domain.export.destination
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.avyaan.deckle.data.R
import com.avyaan.deckle.data.files.AtomicFiles
import com.avyaan.deckle.data.files.FileLayout
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.export.ExportProgress
import com.avyaan.deckle.domain.export.ExportRequest
import com.avyaan.deckle.domain.export.ExportScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * FR-56: exports run in WorkManager so they survive the user leaving the app. The request (never the
 * password) is written as JSON to cache; WorkData only carries its id (spec 04 §5.2).
 */
public class WorkManagerExportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layout: FileLayout,
    private val secrets: ExportSecrets,
) : ExportScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun enqueue(request: ExportRequest, password: CharArray?) {
        AtomicFiles.write(requestFile(layout, request.exportId)) { it.write(Json.encodeToString(ExportRequest.serializer(), request).toByteArray()) }
        if (password != null) secrets.put(request.exportId, password)
        val work = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(workDataOf(ExportWorker.KEY_EXPORT_ID to request.exportId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(ExportWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(ExportWorker.uniqueName(request.exportId), ExistingWorkPolicy.REPLACE, work)
    }

    override fun observe(exportId: String): Flow<ExportProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(ExportWorker.uniqueName(exportId)).map { infos ->
            val info = infos.lastOrNull() ?: return@map ExportProgress.Queued
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ExportProgress.Queued
                WorkInfo.State.RUNNING -> {
                    val total = info.progress.getInt(ExportWorker.KEY_TOTAL, 0)
                    if (total == 0) ExportProgress.Queued else ExportProgress.Running(info.progress.getInt(ExportWorker.KEY_DONE, 0), total)
                }
                WorkInfo.State.SUCCEEDED -> ExportProgress.Succeeded(
                    info.outputData.getStringArray(ExportWorker.KEY_OUTPUTS)?.toList().orEmpty(),
                    info.outputData.getString(ExportWorker.KEY_MIME) ?: "application/octet-stream",
                )
                WorkInfo.State.FAILED -> ExportProgress.Failed(DeckleError.ExportFailed(info.outputData.getString(ExportWorker.KEY_ERROR) ?: "unknown"))
                WorkInfo.State.CANCELLED -> ExportProgress.Failed(DeckleError.Cancelled)
            }
        }

    override fun cancel(exportId: String) {
        secrets.discard(exportId)
        workManager.cancelUniqueWork(ExportWorker.uniqueName(exportId))
    }

    internal companion object {
        fun requestFile(layout: FileLayout, exportId: String) = layout.cacheFile("export-requests/$exportId.json")
    }
}

@HiltWorker
public class ExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: ExportRunner,
    private val secrets: ExportSecrets,
    private val layout: FileLayout,
    private val history: ExportHistoryRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val exportId = inputData.getString(KEY_EXPORT_ID) ?: return failure("no-id")
        val requestFile = WorkManagerExportScheduler.requestFile(layout, exportId)
        val request = runCatching { Json.decodeFromString(ExportRequest.serializer(), requestFile.readText()) }.getOrNull()
            ?: return failure("no-request")
        val password = if (request.hasPassword) secrets.take(exportId) ?: return failure(PASSWORD_LOST) else null

        runCatching { setForeground(foregroundInfo(0, 0)) }   // may be refused in the background; the export still runs
        return try {
            val result = runner.run(request, password) { done, total ->
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                ExportNotifications.notify(applicationContext, ExportNotifications.ID_PROGRESS, ExportNotifications.progress(applicationContext, done, total))
            }
            when (result) {
                is Outcome.Success -> {
                    // History is a convenience: failing to record it must not fail a finished export.
                    runCatching { history.add(request.toRecord(result.value)) }
                    ExportNotifications.done(applicationContext)
                    Result.success(workDataOf(KEY_OUTPUTS to result.value.outputs.toTypedArray(), KEY_MIME to result.value.mimeType))
                }
                is Outcome.Failure -> failure((result.error as? DeckleError.ExportFailed)?.cause ?: result.error.javaClass.simpleName)
            }
        } finally {
            password?.fill('\u0000')   // FR-55: never kept longer than needed
            requestFile.delete()
            NotificationManagerCompat.from(applicationContext).cancel(ExportNotifications.ID_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    /** D-7: mediaProcessing on API 35+, dataSync before it. */
    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = ExportNotifications.progress(applicationContext, done, total)
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(ExportNotifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(ExportNotifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(ExportNotifications.ID_PROGRESS, notification)
        }
    }

    private fun failure(reason: String) = Result.failure(workDataOf(KEY_ERROR to reason))

    private fun ExportRequest.toRecord(output: ExportOutput) = ExportRecord(
        id = exportId,
        createdAt = clock.now(),
        titles = output.titles,
        documentIds = documentIds,
        pageCount = output.pageCount,
        format = format,
        destination = target.destination(),
        outputs = output.outputs,
        mimeType = output.mimeType,
        passwordProtected = hasPassword,
    )

    public companion object {
        public const val TAG: String = "export"
        public const val KEY_EXPORT_ID: String = "exportId"
        public const val KEY_DONE: String = "done"
        public const val KEY_TOTAL: String = "total"
        public const val KEY_OUTPUTS: String = "outputs"
        public const val KEY_MIME: String = "mime"
        public const val KEY_ERROR: String = "error"
        public const val PASSWORD_LOST: String = "password-lost"
        public fun uniqueName(exportId: String): String = "export-$exportId"
    }
}

/** The export notification channel and its two notifications (progress, done). */
public object ExportNotifications {
    public const val CHANNEL_ID: String = "export"
    internal const val ID_PROGRESS = 1001
    private const val ID_DONE = 1002

    public fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_export), NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null) }
        manager.createNotificationChannel(channel)
    }

    internal fun progress(context: Context, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(context.getString(R.string.notification_export_running))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    internal fun done(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(context.getString(R.string.notification_export_done))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_DONE, notification)
    }

    /** FR-56: without POST_NOTIFICATIONS the export still runs; only the notification is skipped. */
    @SuppressLint("MissingPermission") // checked just below; lint misses the check inside the SDK guard
    internal fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

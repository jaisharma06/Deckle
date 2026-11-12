package com.avyaan.deckle.data.ocr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.RunOcrUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** FR-64: unique work per page, constrained only by battery-not-low. Never blocks capture or UI. */
public class WorkManagerOcrScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun enqueue(pageIds: List<String>) {
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        pageIds.forEach { id ->
            val request = OneTimeWorkRequestBuilder<OcrWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(OcrWorker.KEY_PAGE_ID to id))
                .addTag(OcrWorker.TAG)
                .build()
            workManager.enqueueUniqueWork(OcrWorker.uniqueName(id), ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun cancel(pageId: String) {
        workManager.cancelUniqueWork(OcrWorker.uniqueName(pageId))
    }
}

/**
 * Runs [RunOcrUseCase] for one page. Returns success even when OCR fails: FR-65 specifies the retry policy
 * (once, on next launch), so WorkManager's own backoff must not retry as well.
 */
@HiltWorker
public class OcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runOcr: RunOcrUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pageId = inputData.getString(KEY_PAGE_ID) ?: return Result.success()
        runOcr(pageId)
        return Result.success()
    }

    public companion object {
        public const val KEY_PAGE_ID: String = "pageId"
        public const val TAG: String = "ocr"
        public fun uniqueName(pageId: String): String = "ocr-$pageId"
    }
}

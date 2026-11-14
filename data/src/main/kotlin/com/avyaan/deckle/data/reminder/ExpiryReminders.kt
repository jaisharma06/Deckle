package com.avyaan.deckle.data.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.avyaan.deckle.data.R
import com.avyaan.deckle.domain.reminder.CheckExpiryRemindersUseCase
import com.avyaan.deckle.domain.reminder.ReminderNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * NV-32: expiry reminders as local notifications. Nothing is scheduled on a server; a daily WorkManager job
 * checks the dates. Tapping a reminder opens the document ([EXTRA_DOCUMENT_ID] on the launch intent).
 */
public class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier {

    override fun notifyExpiry(documentId: String, title: String, daysLeft: Long) {
        val text = when {
            daysLeft < 0 -> context.getString(R.string.reminder_expired)
            daysLeft == 0L -> context.getString(R.string.reminder_today)
            else -> context.resources.getQuantityString(R.plurals.reminder_days_left, daysLeft.toInt(), daysLeft.toInt())
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(context.getString(R.string.reminder_title, title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openDocument(documentId))
            .build()
        post(documentId.hashCode(), notification)
    }

    /** Without POST_NOTIFICATIONS the reminder is skipped; the "Expiring soon" collection still shows it. */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openDocument(documentId: String): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_DOCUMENT_ID, documentId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: return null
        return PendingIntent.getActivity(context, documentId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    public companion object {
        public const val CHANNEL_ID: String = "reminders"
        public const val EXTRA_DOCUMENT_ID: String = "com.avyaan.deckle.documentId"

        public fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

@HiltWorker
public class ExpiryReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: CheckExpiryRemindersUseCase,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching { check() }.fold({ Result.success() }, { Result.retry() })

    public companion object {
        private const val NAME = "expiry-reminders"

        /** Idempotent: keeps an existing schedule, so calling it on every launch is cheap. */
        public fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}

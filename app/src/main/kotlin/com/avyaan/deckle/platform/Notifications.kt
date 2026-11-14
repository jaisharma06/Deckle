package com.avyaan.deckle.platform

import android.content.Context
import com.avyaan.deckle.data.export.ExportNotifications

/** Creates the app's notification channels at startup. Each channel is owned by the :data code that posts to it. */
object Notifications {
    fun createChannels(context: Context) {
        ExportNotifications.createChannel(context)
        com.avyaan.deckle.data.reminder.AndroidReminderNotifier.createChannel(context)
    }
}

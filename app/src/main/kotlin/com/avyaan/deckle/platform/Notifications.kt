package com.avyaan.deckle.platform

import android.content.Context
import com.avyaan.deckle.data.export.ExportNotifications

/** Creates the app's notification channels at startup. The export channel is owned by the export worker. */
object Notifications {
    fun createChannels(context: Context) {
        ExportNotifications.createChannel(context)
    }
}

package com.avyaan.deckle

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.avyaan.deckle.data.DataInitializer
import com.avyaan.deckle.domain.common.ApplicationScope
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.storage.RunStartupMaintenanceUseCase
import com.avyaan.deckle.platform.Notifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class DeckleApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var maintenance: RunStartupMaintenanceUseCase
    @Inject lateinit var dispatchers: DispatcherProvider
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // Order matters (requirements: "Initialisation order"): OpenCV, then PDFBox, then Hilt.
        DataInitializer.init(this)
        super.onCreate()   // Hilt injection happens here
        Notifications.createChannels(this)
        // 3. Housekeeping off the startup path: never blocks the first frame.
        appScope.launch(dispatchers.io) { maintenance() }
    }
}

package com.avyaan.deckle

import android.content.Intent
import android.os.Bundle
import com.avyaan.deckle.data.reminder.AndroidReminderNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avyaan.deckle.designsystem.theme.LocalHapticsEnabled
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.domain.settings.SettingsRepository
import javax.inject.Inject
import com.avyaan.deckle.designsystem.theme.DeckleTheme
import com.avyaan.deckle.navigation.DeckleNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. It declares orientation/size config changes (D-6) so rotating during capture
 * neither rebinds the camera nor resets the detection overlay; all other changes recreate it and every
 * screen restores from SavedStateHandle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    /** NV-32: the document a tapped reminder asks for; cleared once the screen is open. */
    private val pendingDocument = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a configuration change the back stack already holds the document.
        if (savedInstanceState == null) pendingDocument.value = intent.documentToOpen()
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            CompositionLocalProvider(LocalHapticsEnabled provides s.vibration) {
                DeckleTheme {
                    val open by pendingDocument.collectAsStateWithLifecycle()
                    DeckleNavHost(openDocumentId = open, onDocumentOpened = { pendingDocument.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.documentToOpen()?.let { pendingDocument.value = it }
    }

    private fun Intent.documentToOpen(): String? =
        getStringExtra(AndroidReminderNotifier.EXTRA_DOCUMENT_ID).also { removeExtra(AndroidReminderNotifier.EXTRA_DOCUMENT_ID) }
}

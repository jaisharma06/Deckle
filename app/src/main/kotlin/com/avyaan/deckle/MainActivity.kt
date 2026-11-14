package com.avyaan.deckle

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            CompositionLocalProvider(LocalHapticsEnabled provides s.vibration) {
                DeckleTheme {
                    DeckleNavHost()
                }
            }
        }
    }
}

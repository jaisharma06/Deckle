package com.avyaan.deckle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DeckleTheme {
                DeckleNavHost()
            }
        }
    }
}

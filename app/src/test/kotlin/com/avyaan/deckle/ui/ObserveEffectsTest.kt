package com.avyaan.deckle.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObserveEffectsTest {

    /**
     * Regression: an undo snackbar suspends its handler until dismissed. Effects sent meanwhile (taps on
     * "Scan") used to queue behind it and all fire at once when Undo was tapped, opening the camera N times.
     */
    @Test
    fun `a long-running effect does not hold back the ones after it`() {
        val effects = Channel<String>(Channel.BUFFERED)
        val handled = mutableListOf<String>()
        val snackbarDismissed = CompletableDeferred<Unit>()
        // Built directly (no manifest entry needed), so the test runs in debug and release unit tests.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ObserveEffects(effects.receiveAsFlow()) { effect ->
                handled += effect
                if (effect == "snackbar") snackbarDismissed.await()
            }
        }
        ShadowLooper.idleMainLooper()

        effects.trySend("snackbar")
        effects.trySend("open-camera")
        ShadowLooper.idleMainLooper()

        assertThat(handled).containsExactly("snackbar", "open-camera").inOrder()
        snackbarDismissed.complete(Unit)
    }
}

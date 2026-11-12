package com.avyaan.deckle.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects one-shot ViewModel effects while the screen is at least STARTED (delivered exactly once).
 * Each effect is handled in its own coroutine, in arrival order: a handler that suspends for a long time
 * (an undo snackbar waits until it is dismissed) must not hold back the effects behind it — they used to
 * pile up and all run at once when the snackbar went away (e.g. the camera opening once per earlier tap).
 */
@Composable
fun <T> ObserveEffects(effects: Flow<T>, onEffect: suspend (T) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handler by rememberUpdatedState(onEffect)
    LaunchedEffect(effects, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { effect -> launch(start = CoroutineStart.UNDISPATCHED) { handler(effect) } }
        }
    }
}

/**
 * FR-80: keeps scanned content out of screenshots and the recent-apps thumbnail while this screen is shown.
 * Driven by the user setting (default on).
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled, activity) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

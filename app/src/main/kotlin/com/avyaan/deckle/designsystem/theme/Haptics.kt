package com.avyaan.deckle.designsystem.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Semantic haptics (spec 05 §6). Screens call meaning, not constants; API fallbacks live here.
 * Respects the system haptics setting (we never pass FLAG_IGNORE_GLOBAL_SETTING).
 */
public class DeckleHaptics internal constructor(private val view: View, private val enabled: Boolean = true) {
    /** Capture, export success, document saved. */
    public fun confirm(): Unit = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** Invalid crop move, empty rename. */
    public fun reject(): Unit = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** Filter switch, handle snap, toggles. */
    public fun tick(): Unit = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Enter selection mode, start a reorder drag. */
    public fun select(): Unit = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Grabbing a crop handle. */
    public fun gestureStart(): Unit = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
public fun rememberHaptics(): DeckleHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { DeckleHaptics(view, enabled) }
}

/** NV-05: the in-app Vibration setting, provided once at the activity root. */
public val LocalHapticsEnabled = staticCompositionLocalOf { true }

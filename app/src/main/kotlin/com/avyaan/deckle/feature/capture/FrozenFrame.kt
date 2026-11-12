package com.avyaan.deckle.feature.capture

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Hands the on-screen frame at shutter time to the crop editor (spec 03 §6.1): the editor shows it at once
 * and crossfades to `preview.jpg` when the still lane is done, which is how shutter → editor stays under
 * ~800 ms.
 *
 * In-memory only, deliberately: after process death the preview file exists anyway, and a bitmap must not
 * be serialised. Taking the frame clears it so it is never shown for the wrong page.
 */
object FrozenFrame {
    @Volatile private var frame: ImageBitmap? = null

    fun put(bitmap: ImageBitmap?) {
        frame = bitmap
    }

    fun take(): ImageBitmap? = frame.also { frame = null }
}

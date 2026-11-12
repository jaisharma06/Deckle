package com.avyaan.deckle.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes an app-private image straight into an [ImageBitmap], downsampled to about [maxEdge] pixels.
 * Used where we need pixels (the crop loupe), not just a picture — Coil covers everything else.
 * Our files are already upright and EXIF-free (the normaliser rewrote them), so no orientation handling.
 */
suspend fun decodeImageBitmap(path: String, maxEdge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

@Composable
fun rememberImageBitmap(path: String?, maxEdge: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, path, maxEdge) {
        value = path?.let { decodeImageBitmap(it, maxEdge) }
    }

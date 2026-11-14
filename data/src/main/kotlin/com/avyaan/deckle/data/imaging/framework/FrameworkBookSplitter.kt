package com.avyaan.deckle.data.imaging.framework

import android.graphics.Bitmap
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.data.imaging.common.guarded
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.imaging.BookSplitter
import javax.inject.Inject

/** NV-12 degraded mode (no OpenCV): split down the middle, no spine search or curl flattening. */
public class FrameworkBookSplitter @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : BookSplitter {
    override suspend fun split(spreadPath: String, leftOut: String, rightOut: String): Outcome<Unit> = guarded(dispatchers) {
        val spread = io.decodeFile(spreadPath) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
        try {
            val mid = spread.width / 2
            listOf(leftOut to 0, rightOut to mid).forEach { (out, x) ->
                val half = Bitmap.createBitmap(spread, x, 0, if (x == 0) mid else spread.width - mid, spread.height)
                try { io.writeJpeg(half, out, 95) } finally { half.recycle() }
            }
            Outcome.Success(Unit)
        } finally {
            spread.recycle()
        }
    }
}

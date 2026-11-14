package com.avyaan.deckle.data.imaging.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.imaging.CardComposer
import javax.inject.Inject

/**
 * NV-11 with plain Canvas drawing: no OpenCV needed, so it works in degraded mode too.
 * The page is A4 at 300 dpi; each side fills a card-sized box in its own orientation (a portrait ID stays
 * portrait), keeping its aspect ratio, so the printout matches the real card.
 */
public class AndroidCardComposer @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : CardComposer {

    override suspend fun compose(frontPath: String, backPath: String?, out: String): Outcome<ImageSize> = guarded(dispatchers) {
        val front = io.decodeFile(frontPath, SIDE_MAX_EDGE) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
        val back = backPath?.let { io.decodeFile(it, SIDE_MAX_EDGE) ?: run { front.recycle(); return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed) } }
        val page = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            var top = TOP_MARGIN
            listOfNotNull(front, back).forEach { side ->
                val box = cardBox(side)
                val dst = fit(side, RectF((PAGE_W - box.width()) / 2f, top.toFloat(), (PAGE_W + box.width()) / 2f, top + box.height().toFloat()))
                canvas.drawBitmap(side, Rect(0, 0, side.width, side.height), dst, paint)
                top += box.height() + GAP
            }
            io.writeJpeg(page, out, QUALITY)
            Outcome.Success(ImageSize(PAGE_W, PAGE_H))
        } finally {
            page.recycle(); front.recycle(); back?.recycle()
        }
    }

    /** The real card size in page pixels, turned to match the photo's orientation. */
    private fun cardBox(side: Bitmap): Rect =
        if (side.height > side.width) Rect(0, 0, CARD_SHORT, CARD_LONG) else Rect(0, 0, CARD_LONG, CARD_SHORT)

    /** Largest rect with the bitmap's aspect inside [box], centred. */
    private fun fit(b: Bitmap, box: RectF): RectF {
        val s = minOf(box.width() / b.width, box.height() / b.height)
        val w = b.width * s
        val h = b.height * s
        return RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
    }

    private companion object {
        // A4 at 300 dpi.
        const val PAGE_W = 2480
        const val PAGE_H = 3508
        // ID-1 card: 85.6 × 53.98 mm at 300 dpi.
        const val CARD_LONG = 1011
        const val CARD_SHORT = 638
        const val TOP_MARGIN = 350
        const val GAP = 160
        /** Plenty for a card box of ~1000 px; keeps memory small. */
        const val SIDE_MAX_EDGE = 2000
        const val QUALITY = 92
    }
}

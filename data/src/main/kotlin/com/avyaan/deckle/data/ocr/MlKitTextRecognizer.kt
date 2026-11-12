package com.avyaan.deckle.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.TextRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * FR-61: bundled Latin model (`com.google.mlkit:text-recognition`), statically linked, works with no
 * Play Services and no network. One client for the process lifetime.
 */
@Singleton
public class MlKitTextRecognizer @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : TextRecognizer {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(greyImagePath: String): Outcome<List<OcrLine>> = withContext(dispatchers.default) {
        val bitmap = BitmapFactory.decodeFile(greyImagePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return@withContext Outcome.Failure(DeckleError.ImageDecodeFailed)
        try {
            val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            Outcome.Success(text.toLines(bitmap.width, bitmap.height))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DeckleError.OcrFailed(e.javaClass.simpleName))
        } finally {
            bitmap.recycle()
        }
    }
}

/** FR-62: lines only (word boxes would triple the payload), boxes normalised 0..1 (FR-63). */
internal fun Text.toLines(width: Int, height: Int): List<OcrLine> {
    val w = width.toFloat()
    val h = height.toFloat()
    return textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox ?: return@mapNotNull null
        OcrLine(
            text = line.text,
            box = RectN(
                (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f),
            ),
            confidence = line.confidence,
        )
    }
}

/** Our own tiny bridge, instead of kotlinx-coroutines-play-services: one fewer artifact to audit. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

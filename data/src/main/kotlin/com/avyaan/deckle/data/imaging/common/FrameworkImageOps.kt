package com.avyaan.deckle.data.imaging.common

import android.graphics.Matrix
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.DetectionTuning
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.ImageRotator
import com.avyaan.deckle.domain.imaging.ThumbnailMaker
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Engine-independent image operations; plain Android graphics is enough for these. */

/** Decode → upright → ≤ 3000 px → re-encode without EXIF (spec 03 §6, FR-81). Also writes the preview copy. */
public class AndroidImageNormalizer @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : ImageNormalizer {
    override suspend fun normalize(input: ImageInput, sourceOut: String, previewOut: String, previewLongEdge: Int): Outcome<ImageSize> =
        guarded(dispatchers) {
            val source = io.decodeUpright(input, DetectionTuning.MAX_SOURCE_LONG_EDGE_PX) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
            try {
                io.writeJpeg(source, sourceOut, SOURCE_QUALITY)
                val preview = BitmapIO.scaledCopy(source, previewLongEdge)
                try { io.writeJpeg(preview, previewOut, PREVIEW_QUALITY) } finally { if (preview !== source) preview.recycle() }
                Outcome.Success(ImageSize(source.width, source.height))
            } finally {
                source.recycle()
            }
        }

    private companion object {
        const val SOURCE_QUALITY = 95
        const val PREVIEW_QUALITY = 85
    }
}

public class AndroidThumbnailMaker @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : ThumbnailMaker {
    override suspend fun make(imagePath: String, out: String, longEdge: Int): Outcome<Unit> = guarded(dispatchers) {
        val bmp = io.decodeFile(imagePath, longEdge) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
        try { io.writeJpeg(bmp, out, 80) } finally { bmp.recycle() }
        Outcome.Success(Unit)
    }
}

/** Quarter-turn rotation of a stored page (FR-42). */
public class AndroidImageRotator @Inject constructor(
    private val io: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : ImageRotator {
    override suspend fun rotate(path: String, rotation: Rotation, out: String): Outcome<ImageSize> = guarded(dispatchers) {
        val src = io.decodeFile(path) ?: return@guarded Outcome.Failure(DeckleError.ImageDecodeFailed)
        val rotated = BitmapIO.transform(src, Matrix().apply { postRotate(rotation.degrees.toFloat()) })
        try {
            io.writeJpeg(rotated, out, 92)
            Outcome.Success(ImageSize(rotated.width, rotated.height))
        } finally {
            rotated.recycle()
        }
    }
}

/** Runs on the imaging dispatcher and turns expected failures into [Outcome]s (spec 01 §8). */
internal suspend fun <T> guarded(dispatchers: DispatcherProvider, block: suspend () -> Outcome<T>): Outcome<T> =
    withContext(dispatchers.imaging) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Outcome.Failure(DeckleError.Unexpected(e))
        } catch (e: IOException) {
            Outcome.Failure(if (e.message?.contains("ENOSPC") == true) DeckleError.StorageFull else DeckleError.Unexpected(e))
        } catch (e: Exception) {
            Outcome.Failure(DeckleError.Unexpected(e))
        }
    }

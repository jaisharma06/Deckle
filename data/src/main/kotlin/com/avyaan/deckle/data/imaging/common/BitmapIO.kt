package com.avyaan.deckle.data.imaging.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.avyaan.deckle.data.files.AtomicFiles
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.imaging.ImageInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap decode/encode with memory discipline: always decode with `inSampleSize` so a 108 MP image never
 * lands on the heap at full size (requirements: "Memory").
 */
public class BitmapIO @Inject constructor(@ApplicationContext private val context: Context) {

    /** Decodes [input] upright (EXIF applied) with its long edge at most [maxLongEdge]. Caller recycles. */
    public fun decodeUpright(input: ImageInput, maxLongEdge: Int): Bitmap? {
        val orientation = open(input)?.use { exifOrientation(it) } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null by design; success is read from outWidth/outHeight.
        (open(input) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = open(input)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return applyOrientation(scaleDown(sampled, maxLongEdge), orientation)
    }

    /** Decodes a file we wrote ourselves (already upright, no EXIF). */
    public fun decodeFile(path: String, maxLongEdge: Int? = null): Bitmap? {
        if (maxLongEdge == null) return BitmapFactory.decodeFile(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), maxLongEdge) }
        val sampled = BitmapFactory.decodeFile(path, opts) ?: return null
        return scaleDown(sampled, maxLongEdge)
    }

    public fun sizeOf(path: String): ImageSize? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0) ImageSize(o.outWidth, o.outHeight) else null
    }

    /** Writes a JPEG atomically. EXIF is never written, which also strips GPS (FR-81). */
    public fun writeJpeg(bitmap: Bitmap, path: String, quality: Int) {
        AtomicFiles.write(File(path)) { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed" } }
    }

    public fun writePng(bitmap: Bitmap, path: String) {
        AtomicFiles.write(File(path)) { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" } }
    }

    private fun open(input: ImageInput): InputStream? = when (input) {
        is ImageInput.JpegBytes -> ByteArrayInputStream(input.bytes)
        is ImageInput.FilePath -> File(input.path).takeIf { it.exists() }?.inputStream()
        is ImageInput.ContentUri -> context.contentResolver.openInputStream(Uri.parse(input.uri))
    }

    private fun exifOrientation(stream: InputStream): Int =
        runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return transform(src, m)
    }

    public companion object {
        /** Largest power of two that keeps the decoded long edge at or above [target] (then we scale exactly). */
        public fun sampleSizeFor(longEdge: Int, target: Int): Int {
            var sample = 1
            while (longEdge / (sample * 2) >= target) sample *= 2
            return sample
        }

        public fun scaleDown(src: Bitmap, maxLongEdge: Int): Bitmap {
            val long = max(src.width, src.height)
            if (long <= maxLongEdge) return src
            val s = maxLongEdge.toFloat() / long
            val out = Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
            if (out !== src) src.recycle()
            return out
        }

        /** Like [scaleDown] but leaves [src] intact. Returns [src] itself when it is already small enough. */
        public fun scaledCopy(src: Bitmap, maxLongEdge: Int): Bitmap {
            val long = max(src.width, src.height)
            if (long <= maxLongEdge) return src
            val s = maxLongEdge.toFloat() / long
            return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
        }

        public fun transform(src: Bitmap, m: Matrix): Bitmap {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            return out
        }
    }
}

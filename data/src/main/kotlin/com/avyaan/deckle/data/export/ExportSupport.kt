package com.avyaan.deckle.data.export

import android.graphics.Bitmap
import com.avyaan.deckle.data.imaging.common.BitmapIO
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.export.JpegSizeProbe
import com.avyaan.deckle.domain.export.QualityPreset
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** FR-52 estimate: re-encodes one page at the preset, counting bytes without keeping them. */
public class AndroidJpegSizeProbe @Inject constructor(
    private val bitmaps: BitmapIO,
    private val dispatchers: DispatcherProvider,
) : JpegSizeProbe {
    private val cache = ConcurrentHashMap<String, Long>()

    override suspend fun encodedSize(imagePath: String, quality: QualityPreset): Long? {
        val maxEdge = quality.maxLongEdge ?: return null
        val jpegQuality = quality.jpegQuality ?: return null
        val key = "$imagePath|$quality"
        cache[key]?.let { return it }
        return withContext(dispatchers.io) {
            val bitmap = bitmaps.decodeFile(imagePath, maxEdge) ?: return@withContext null
            try {
                val counter = CountingStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, counter)
                counter.count.also { cache[key] = it }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private class CountingStream : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }
}

/**
 * Holds export passwords in memory only, keyed by export id (spec 04 §5.2). WorkData is persisted to disk,
 * so the password can never travel there. If the process dies first, the worker fails with
 * "password-lost" and the user exports again — the password is never written anywhere.
 */
@Singleton
public class ExportSecrets @Inject constructor() {
    private val secrets = ConcurrentHashMap<String, CharArray>()

    public fun put(exportId: String, password: CharArray) {
        secrets[exportId] = password.copyOf()
    }

    /** Removes and returns the password; the caller zeroes it after use. */
    public fun take(exportId: String): CharArray? = secrets.remove(exportId)

    public fun discard(exportId: String) {
        secrets.remove(exportId)?.fill('\u0000')
    }
}

package com.avyaan.deckle.data.imaging.opencv

import com.avyaan.deckle.data.files.AtomicFiles
import java.io.File
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs

/**
 * Owns native allocations (spec 03 §5). `Mat` is not AutoCloseable and lives outside the GC's view;
 * leaking them crashes the app after a few dozen pages. Rules:
 * 1. every Mat is created via [mat] or tracked with [track] inside a [matScope];
 * 2. no Mat escapes the scope (not returned from a public function, not stored in a field).
 */
public class MatScope : AutoCloseable {
    private val owned = ArrayList<Mat>(16)

    public fun <T : Mat> T.track(): T = also { owned += it }

    public fun mat(): Mat = Mat().track()

    override fun close() {
        for (i in owned.indices.reversed()) owned[i].release()
        owned.clear()
    }
}

public inline fun <R> matScope(block: MatScope.() -> R): R = MatScope().use(block)

/** Encodes to JPEG in memory, then writes atomically (imwrite picks the codec from the extension, so no .tmp names). */
internal fun MatScope.writeJpeg(mat: Mat, path: String, quality: Int) {
    val buffer = MatOfByte().track()
    check(Imgcodecs.imencode(".jpg", mat, buffer, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality).track())) { "JPEG encode failed" }
    val bytes = buffer.toArray()
    AtomicFiles.write(File(path)) { it.write(bytes) }
}

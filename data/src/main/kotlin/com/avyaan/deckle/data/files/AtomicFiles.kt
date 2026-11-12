package com.avyaan.deckle.data.files

import java.io.File
import java.io.FilterOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Write-to-temp-then-rename (spec 02 §4.2). A crash mid-write leaves a `.tmp` the janitor removes,
 * never a truncated JPEG that a database row points at.
 */
public object AtomicFiles {
    public const val TMP_SUFFIX: String = ".tmp"

    public fun write(target: File, block: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                // Some writers close the stream they are given (PDFBox's PDDocument.save does), which would
                // leave nothing to sync. They get a view whose close() only flushes; we close after syncing.
                block(NonClosingStream(out))
                out.flush()
                out.fd.sync()
            }
            replace(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    /** A temp path next to [target] for libraries that want to write a file themselves. */
    public fun tempFor(target: File): File {
        target.parentFile?.mkdirs()
        return File(target.parentFile, target.name + TMP_SUFFIX)
    }

    /** Renames [tmp] over [target]; atomic on the same filesystem. */
    public fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
        }
    }

    private class NonClosingStream(out: OutputStream) : FilterOutputStream(out) {
        // FilterOutputStream writes arrays byte by byte; pass them through whole.
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** Moves within app storage (rename), falling back to copy + delete across filesystems. */
    public fun move(from: File, to: File) {
        if (from == to) return
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        write(to) { out -> from.inputStream().use { it.copyTo(out) } }
        from.delete()
    }
}

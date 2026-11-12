package com.avyaan.deckle.data.export

import com.avyaan.deckle.data.files.AtomicFiles
import com.avyaan.deckle.domain.common.DispatcherProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.withContext

/** Packs export parts into one ZIP for folder exports; entry names are the files' paths below [root]. */
internal object ZipPacker {
    suspend fun pack(root: File, files: List<File>, out: File, dispatchers: DispatcherProvider) = withContext(dispatchers.io) {
        AtomicFiles.write(out) { stream ->
            // PDFs and JPEGs are already compressed; deflating them again costs time for ~nothing.
            ZipOutputStream(stream).apply { setLevel(java.util.zip.Deflater.BEST_SPEED) }.use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}

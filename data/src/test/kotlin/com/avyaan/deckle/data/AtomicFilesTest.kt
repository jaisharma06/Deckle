package com.avyaan.deckle.data

import com.avyaan.deckle.data.files.AtomicFiles
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class AtomicFilesTest {
    private val dir: File = Files.createTempDirectory("atomic").toFile()

    @Test
    fun `writes the file and leaves no temp behind`() {
        val target = File(dir, "a.bin")
        AtomicFiles.write(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("a.bin")
    }

    /** PDFBox's PDDocument.save(OutputStream) closes the stream it is given; that used to fail every PDF export. */
    @Test
    fun `a writer that closes the stream itself still succeeds`() {
        val target = File(dir, "b.pdf")
        AtomicFiles.write(target) { out -> out.write(byteArrayOf(9, 8)); out.close() }
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(9, 8))
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("b.pdf")
    }
}

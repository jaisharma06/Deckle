package com.avyaan.deckle.data

import com.avyaan.deckle.data.export.ZipPacker
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ZipPackerTest {
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val imaging = Dispatchers.Unconfined
        override val preview = Dispatchers.Unconfined
    }

    @Test
    fun `folder export keeps the sub-folder layout inside the zip`() = runTest {
        val root = Files.createTempDirectory("parts").toFile()
        val top = File(root, "Lease.pdf").apply { writeText("lease") }
        val deep = File(root, "2025/Q1/Receipt.pdf").apply { parentFile.mkdirs(); writeText("receipt") }
        val out = File(root.parentFile, "${root.name}.zip")

        ZipPacker.pack(root, listOf(top, deep), out, dispatchers)

        ZipFile(out).use { zip ->
            assertThat(zip.entries().toList().map { it.name }).containsExactly("Lease.pdf", "2025/Q1/Receipt.pdf")
            assertThat(zip.getInputStream(zip.getEntry("2025/Q1/Receipt.pdf")).readBytes().decodeToString()).isEqualTo("receipt")
        }
        out.delete()
    }
}

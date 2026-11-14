package com.avyaan.deckle.domain

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.document.DeleteDocumentsPermanentlyUseCase
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakeFileStore
import com.avyaan.deckle.domain.fakes.FakeOcrScheduler
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeScanSessionStore
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.FakeThumbnailMaker
import com.avyaan.deckle.domain.fakes.FakeTransactionRunner
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.ocr.RunOcrUseCase
import com.avyaan.deckle.domain.ocr.TextRecognizer
import com.avyaan.deckle.domain.page.RenamePageUseCase
import com.avyaan.deckle.domain.page.ReorderPageUseCase
import com.avyaan.deckle.domain.scan.CommitScanSessionUseCase
import com.avyaan.deckle.domain.scan.StagedCapture
import com.avyaan.deckle.domain.scan.StagedStatus
import com.avyaan.deckle.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UseCaseTest {
    private val clock = FakeClock()
    private val files = FakeFileStore()
    private val pages = FakePageRepository()
    private val docs = FakeDocumentRepository(pages)
    private val sessions = FakeScanSessionStore()
    private val tx = FakeTransactionRunner()
    private val ocr = FakeOcrScheduler()
    private val settings = FakeSettingsRepository(AppSettings(saveOriginals = false))

    private fun commit() = CommitScanSessionUseCase(
        sessions, docs, pages, files, FakeThumbnailMaker(files), tx, ocr, settings, SequentialIds("page"), clock,
    )

    private suspend fun readySession(count: Int): String {
        val s = sessions.create("s1", 0, null, null)
        val captures = List(count) { i ->
            val id = "c$i"
            listOf("source", "base", "processed").forEach { files.put(files.stagingPath(s.id, id, it)) }
            StagedCapture(
                id = id,
                sourcePath = files.stagingPath(s.id, id, "source"),
                previewPath = files.stagingPath(s.id, id, "preview"),
                basePath = files.stagingPath(s.id, id, "base"),
                processedPath = files.stagingPath(s.id, id, "processed"),
                widthPx = 100, heightPx = 140,
                status = StagedStatus.READY, awaitingNormalize = false,
            )
        }
        sessions.update(s.id) { it.copy(captures = captures) }
        return s.id
    }

    @Test fun `commit creates a titled document, moves files, queues OCR and discards the session`() = runTest {
        val sessionId = readySession(2)
        val result = commit()(sessionId)

        val docId = (result as Outcome.Success).value
        assertThat(docs.get(docId)!!.title).isEqualTo("Scan 2026-11-11 10:00")
        val saved = pages.pages(docId)
        assertThat(saved.map { it.orderIndex }).containsExactly(1000L, 2000L).inOrder()
        assertThat(saved.all { it.sourcePath == null }).isTrue()                 // save originals off
        assertThat(files.files.keys.none { it.contains("/staging/") && !it.contains(".source.") }).isTrue()
        assertThat(ocr.enqueued).containsExactlyElementsIn(saved.map { it.id })
        assertThat(sessions.discarded).containsExactly(sessionId)
    }

    @Test fun `commit rolls files back to staging when the database write fails`() = runTest {
        val sessionId = readySession(1)
        val before = files.files.keys.toSet()
        tx.failNext = true

        val result = commit()(sessionId)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(files.files.keys.filter { it.contains("/staging/") }).containsExactlyElementsIn(before)
        assertThat(sessions.discarded).isEmpty()
        assertThat(pages.all()).isEmpty()
    }

    @Test fun `commit into an existing document inserts after the chosen page`() = runTest {
        docs.create("doc", "Existing", clock.now())
        pages.insert(listOf(page("p1", 1000), page("p2", 2000)))
        sessions.create("s2", 0, "doc", "p1")
        val staged = sessions.get(readySession(1))!!.captures
        sessions.update("s2") { it.copy(captures = staged) }

        commit()("s2")

        val order = pages.pages("doc").map { it.id to it.orderIndex }
        assertThat(order.map { it.first }.drop(0)).containsExactly("p1", "page-1", "p2").inOrder()
    }

    @Test fun `permanent delete removes rows and folder, and a DB failure restores the folder`() = runTest {
        docs.create("doc", "A", clock.now())
        pages.insert(listOf(page("p1", 1000)))
        files.put(files.pathFor("doc", "p1", com.avyaan.deckle.domain.page.PageFileKind.PROCESSED))
        val delete = DeleteDocumentsPermanentlyUseCase(docs, files, tx)

        tx.failNext = true
        runCatching { delete(listOf("doc")) }
        assertThat(files.files.keys.single()).startsWith("/files/docs/doc/")
        assertThat(docs.get("doc")).isNotNull()

        delete(listOf("doc"))
        assertThat(files.files).isEmpty()
        assertThat(docs.get("doc")).isNull()
        assertThat(pages.all()).isEmpty()
    }

    @Test fun `deleting the last page removes the document, earlier deletes keep it`() = runTest {
        docs.create("doc", "A", clock.now())
        pages.insert(listOf(page("p1", 1000), page("p2", 2000)))
        val delete = deletePage()

        delete("p1")
        assertThat(docs.get("doc")).isNotNull()

        delete("p2")
        assertThat(docs.get("doc")).isNull()                    // no empty "0 pages" document left behind
        assertThat(docs.observeTrash().first()).isEmpty()       // and nothing empty parked in the trash
    }

    @Test fun `reorder writes one row when there is a gap`() = runTest {
        docs.create("doc", "A", clock.now())
        pages.insert(listOf(page("a", 1000), page("b", 2000), page("c", 3000)))
        ReorderPageUseCase(pages, docs, tx, clock)("doc", "c", 0)
        assertThat(pages.pages("doc").map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(pages.get("c")!!.orderIndex).isEqualTo(500)
    }

    @Test fun `OCR stores lines and plain text, or marks failed`() = runTest {
        docs.create("doc", "A", clock.now())
        pages.insert(listOf(page("p", 1000)))
        val line = OcrLine("Hello", RectN(0f, 0f, 0.5f, 0.1f), 0.9f)
        var fail = false
        val recognizer = object : TextRecognizer {
            override suspend fun recognize(greyImagePath: String) =
                if (fail) Outcome.Failure(DeckleError.OcrFailed("x")) else Outcome.Success(listOf(line))
        }
        val run = RunOcrUseCase(pages, CopyEnhancer(files), recognizer, files)

        run("p")
        assertThat(pages.get("p")!!.ocr.status).isEqualTo(OcrStatus.DONE)
        assertThat(pages.plainText("p")).isEqualTo("Hello")
        assertThat(files.files.keys.none { it.startsWith("/cache/") }).isTrue()

        fail = true
        pages.setOcr("p", com.avyaan.deckle.domain.ocr.OcrState.QUEUED, null)
        run("p")
        assertThat(pages.get("p")!!.ocr.status).isEqualTo(OcrStatus.FAILED)
        assertThat(pages.get("p")!!.ocr.attempts).isEqualTo(1)
    }

    @Test fun `renaming a page cleans the name, a blank name clears it, and the document is touched`() = runTest {
        docs.create("doc", "Doc", clock.instant)
        pages.insert(listOf(page("p", 1)))
        val rename = RenamePageUseCase(pages, docs, clock)

        clock.instant = clock.instant.plusSeconds(60)
        rename("p", "  Front   of ID ")
        assertThat(pages.get("p")!!.name).isEqualTo("Front of ID")
        assertThat(docs.get("doc")!!.modifiedAt).isEqualTo(clock.instant)

        rename("p", "   ")
        assertThat(pages.get("p")!!.name).isNull()
    }

    private fun deletePage() = com.avyaan.deckle.domain.page.DeletePageUseCase(pages, docs, files, ocr, tx, clock, DeleteDocumentsPermanentlyUseCase(docs, files, tx))

    private fun page(id: String, order: Long) = com.avyaan.deckle.domain.page.Page(
        id, "doc", order, null, "/b/$id", "/p/$id", "/t/$id", Quad.FULL,
        com.avyaan.deckle.domain.geometry.Rotation.R0, EnhancementMode.COLOUR, Adjustments.NEUTRAL, 100, 100,
        com.avyaan.deckle.domain.ocr.OcrState.QUEUED,
    )

    private class CopyEnhancer(private val files: FakeFileStore) : PageEnhancer {
        override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String) =
            Outcome.Success(ImageSize(1, 1)).also { files.put(out) }
        override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String) =
            Outcome.Success(out).also { files.put(out) }
        override suspend fun ocrInput(basePath: String, out: String) = Outcome.Success(ImageSize(1, 1)).also { files.put(out) }
    }
}

package com.avyaan.deckle.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.avyaan.deckle.data.db.DeckleDatabase
import com.avyaan.deckle.data.db.RoomDocumentRepository
import com.avyaan.deckle.data.db.RoomFolderRepository
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.data.db.RoomPageRepository
import com.avyaan.deckle.data.db.RoomTransactionRunner
import com.avyaan.deckle.domain.tag.TagColor
import com.avyaan.deckle.domain.tag.Tag
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.data.db.RoomTagRepository
import com.avyaan.deckle.data.files.AndroidFileJanitor
import com.avyaan.deckle.data.files.AndroidPageFileStore
import com.avyaan.deckle.data.files.FileLayout
import com.avyaan.deckle.data.files.JsonScanSessionStore
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.document.DeleteDocumentsPermanentlyUseCase
import com.avyaan.deckle.domain.document.SearchQuery
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.NoopLogger
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.RectN
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.ocr.OcrLine
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageFileKind
import com.avyaan.deckle.domain.scan.StagedCapture
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private object DirectDispatchers : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val imaging = Dispatchers.Unconfined
    override val preview = Dispatchers.Unconfined
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataLayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: DeckleDatabase
    private lateinit var docs: RoomDocumentRepository
    private lateinit var pages: RoomPageRepository
    private val clock = FakeClock()
    private val layout = FileLayout(context)
    private val files = AndroidPageFileStore(layout, DirectDispatchers)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, DeckleDatabase::class.java).allowMainThreadQueries().build()
        docs = RoomDocumentRepository(db)
        pages = RoomPageRepository(db)
        layout.filesRoot.deleteRecursively()
    }

    @After fun tearDown() {
        db.close()
        layout.filesRoot.deleteRecursively()
    }

    private fun page(id: String, doc: String, order: Long) = Page(
        id, doc, order, null,
        files.pathFor(doc, id, PageFileKind.BASE), files.pathFor(doc, id, PageFileKind.PROCESSED), files.pathFor(doc, id, PageFileKind.THUMB),
        Quad.FULL, Rotation.R0, EnhancementMode.COLOUR, Adjustments.NEUTRAL, 100, 140, OcrState.QUEUED,
    )

    @Test fun libraryShowsCoverAndPageCount_andSortsByTitle() = runTest {
        docs.create("a", "Zebra", clock.now())
        docs.create("b", "apple", clock.now().plusSeconds(1))
        pages.insert(listOf(page("p1", "a", 2000), page("p0", "a", 1000)))

        val byTitle = docs.observeLibrary(SortOrder.TITLE_ASC, null).first()
        assertThat(byTitle.map { it.title }).containsExactly("apple", "Zebra").inOrder()
        val zebra = byTitle.last()
        assertThat(zebra.pageCount).isEqualTo(2)
        assertThat(zebra.coverThumbPath).endsWith(File("thumbs", "p0.jpg").path)   // first page by order
    }

    @Test fun librarySortsByPageCount_andFlagsDocumentsWithText() = runTest {
        docs.create("one", "One page", clock.now())
        docs.create("three", "Three pages", clock.now().plusSeconds(1))
        docs.create("two", "Two pages", clock.now().plusSeconds(2))
        pages.insert(listOf(page("o1", "one", 1000)))
        pages.insert(listOf(page("t1", "three", 1000), page("t2", "three", 2000), page("t3", "three", 3000)))
        pages.insert(listOf(page("w1", "two", 1000), page("w2", "two", 2000)))
        pages.setOcr("w2", OcrState(OcrStatus.DONE, listOf(OcrLine("Rent", RectN(0f, 0f, 1f, 0.1f), 0.9f)), 1), "Rent")
        pages.setOcr("o1", OcrState(OcrStatus.DONE, emptyList(), 1), "")     // read, but blank: no text

        val byPages = docs.observeLibrary(SortOrder.PAGES_DESC, null).first()
        assertThat(byPages.map { it.id }).containsExactly("three", "two", "one").inOrder()
        assertThat(byPages.associate { it.id to it.hasText }).containsExactly("three", false, "two", true, "one", false)
    }

    @Test fun searchFindsTitleAndOcrText_withHighlightedSnippet() = runTest {
        docs.create("a", "Lease agreement", clock.now())
        docs.create("b", "Receipts", clock.now())
        pages.insert(listOf(page("p1", "b", 1000)))
        val line = OcrLine("Total paid to Mumbai Electricity", RectN(0f, 0f, 1f, 0.1f), 0.9f)
        pages.setOcr("p1", OcrState(OcrStatus.DONE, listOf(line), 1), "Total paid to Mumbai Electricity")

        val byText = docs.search(SearchQuery.toLike("mumb"), SearchQuery.toFts("mumb"), SortOrder.CREATED_DESC).first()
        assertThat(byText.map { it.id }).containsExactly("b")
        val snippet = byText.single().snippet!!
        assertThat(snippet.highlights.map { snippet.text.substring(it) }).containsExactly("Mumbai")

        val byTitle = docs.search(SearchQuery.toLike("lease"), SearchQuery.toFts("lease"), SortOrder.CREATED_DESC).first()
        assertThat(byTitle.map { it.id }).containsExactly("a")
    }

    @Test fun foldersFilterTheLibrary_andDeletingAFolderDetachesItsDocuments() = runTest {
        val folders = RoomFolderRepository(db)
        folders.create(Folder("f", "Bills", null, clock.now(), clock.now()))
        docs.create("root", "Root", clock.now())
        docs.create("in", "Inside", clock.now(), folderId = "f")
        docs.create("lost", "Lost", clock.now(), folderId = "missing")   // unknown folder → root

        assertThat(docs.observeLibrary(SortOrder.CREATED_DESC, null).first().map { it.id }).containsExactly("root", "lost")
        assertThat(docs.observeLibrary(SortOrder.CREATED_DESC, "f").first().map { it.id }).containsExactly("in")
        assertThat(folders.observeDocumentCounts().first()).containsExactly("f", 1)

        docs.softDelete(listOf("in"), clock.now())
        folders.delete(listOf("f"))
        assertThat(folders.all()).isEmpty()
        assertThat(docs.get("in")!!.folderId).isNull()   // a restore brings it back at the root
    }

    @Test fun softDeleteHidesFromLibrary_andRestoreBringsBack() = runTest {
        docs.create("a", "A", clock.now())
        docs.softDelete(listOf("a"), clock.now())
        assertThat(docs.observeLibrary(SortOrder.CREATED_DESC, null).first()).isEmpty()
        assertThat(docs.observeTrash().first().map { it.id }).containsExactly("a")
        docs.restore(listOf("a"))
        assertThat(docs.observeLibrary(SortOrder.CREATED_DESC, null).first()).hasSize(1)
    }

    @Test fun permanentDeleteRemovesRowsAndFolder_noOrphans() = runTest {
        docs.create("a", "A", clock.now())
        val p = page("p1", "a", 1000)
        pages.insert(listOf(p))
        listOf(p.basePath, p.processedPath, p.thumbPath).forEach { File(it).apply { parentFile!!.mkdirs(); writeText("x") } }

        DeleteDocumentsPermanentlyUseCase(docs, files, RoomTransactionRunner(db))(listOf("a"))

        assertThat(docs.get("a")).isNull()
        assertThat(pages.get("p1")).isNull()                               // cascade
        assertThat(layout.documentDir("a").exists()).isFalse()
        assertThat(layout.tombstone("a").exists()).isFalse()
    }

    @Test fun janitorRestoresTombstoneWhoseRowSurvived_andDeletesOrphans() = runTest {
        docs.create("a", "A", clock.now())
        val p = page("p1", "a", 1000)
        pages.insert(listOf(p))
        File(p.processedPath).apply { parentFile!!.mkdirs(); writeText("x") }
        File(layout.documentDir("a"), "pages/orphan.jpg").writeText("x")
        File(layout.documentDir("ghost"), "pages/x.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        // Simulate a crash right after step 1 of the delete protocol.
        layout.documentDir("a").renameTo(layout.tombstone("a"))

        AndroidFileJanitor(layout, DirectDispatchers, clock, NoopLogger)
            .clean(docs.allIds(), pages.allFilePaths(), emptySet())

        assertThat(File(p.processedPath).exists()).isTrue()
        assertThat(File(layout.documentDir("a"), "pages/orphan.jpg").exists()).isFalse()
        assertThat(layout.documentDir("ghost").exists()).isFalse()
    }

    @Test fun sessionStoreSurvivesARestart() = runTest {
        val store = JsonScanSessionStore(layout, DirectDispatchers, NoopLogger)
        store.create("s", 1L, null, null)
        store.update("s") { it.copy(captures = listOf(StagedCapture("c", "/src", "/prev"))) }

        val reopened = JsonScanSessionStore(layout, DirectDispatchers, NoopLogger)
        assertThat(reopened.get("s")!!.captures.single().id).isEqualTo("c")
        assertThat(reopened.listAll().map { it.id }).containsExactly("s")

        reopened.discard("s")
        assertThat(layout.sessionDir("s").exists()).isFalse()
    }

    @Test fun pageUpdateKeepsOcrPlainText() = runTest {
        docs.create("a", "A", clock.now())
        pages.insert(listOf(page("p1", "a", 1000)))
        pages.setOcr("p1", OcrState(OcrStatus.DONE, emptyList(), 1), "hello world")
        pages.update(pages.get("p1")!!.copy(mode = EnhancementMode.GREYSCALE))
        assertThat(pages.documentText("a").text).isEqualTo("hello world")
    }

    // ---- Schema v4: phases 3 and 4 ----

    @Test fun tagsPinsAndSignaturesReachTheLibraryRows() = runTest {
        val tags = RoomTagRepository(db)
        docs.create("a", "Electricity", clock.now())
        docs.create("b", "Passport", clock.now().plusSeconds(1))
        pages.insert(listOf(page("pa", "a", 1000), page("pb", "b", 1000)))
        tags.create(Tag("bills", "Bills", TagColor.AMBER, clock.now()))
        tags.create(Tag("ids", "IDs", TagColor.SKY, clock.now()))
        tags.setTags("a", setOf("bills", "ids"))
        docs.setPinned(listOf("b"), true)
        val sig = MarkupItem.Signature("s", SignatureInk(listOf(listOf(0f, 0f, 1f, 0.2f)), 0.3f), 0.5f, 0.8f, 0.3f)
        pages.setMarkup("pb", PageMarkup(listOf(sig)))

        val rows = docs.observeAllLive().first().associateBy { it.id }
        assertThat(rows.getValue("a").tagIds).containsExactly("bills", "ids")
        assertThat(rows.getValue("a").signed).isFalse()
        assertThat(rows.getValue("b").pinned).isTrue()
        assertThat(rows.getValue("b").signed).isTrue()
        assertThat(tags.observeTagsOf("a").first()).containsExactly("bills", "ids")

        tags.delete("ids")                                              // links go with the tag
        assertThat(docs.observeAllLive().first().first { it.id == "a" }.tagIds).containsExactly("bills")
        tags.setTags("a", emptySet())
        assertThat(docs.observeAllLive().first().first { it.id == "a" }.tagIds).isEmpty()
    }

    @Test fun purgingADocumentRemovesItsTagLinks() = runTest {
        val tags = RoomTagRepository(db)
        docs.create("a", "Old bill", clock.now())
        tags.create(Tag("bills", "Bills", TagColor.AMBER, clock.now()))
        tags.addTag(listOf("a"), "bills")
        tags.addTag(listOf("a"), "bills")                               // twice is harmless
        DeleteDocumentsPermanentlyUseCase(docs, files, RoomTransactionRunner(db))(listOf("a"))
        db.query("SELECT COUNT(*) FROM document_tags", null).use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
        assertThat(tags.all()).hasSize(1)
    }

    @Test fun pageEditsKeepTheMarkup_andExpiryIsStored() = runTest {
        docs.create("a", "Insurance", clock.now())
        pages.insert(listOf(page("p1", "a", 1000)))
        val markup = PageMarkup(listOf(MarkupItem.Redaction("r", 0.1f, 0.1f, 0.4f, 0.2f)))
        pages.setMarkup("p1", markup)
        pages.update(pages.get("p1")!!.copy(mode = EnhancementMode.GREYSCALE, markup = null))   // a stale copy
        assertThat(pages.get("p1")!!.markup).isEqualTo(markup)

        val at = clock.now().plusSeconds(86_400L * 40)
        docs.setExpiry("a", at, 90)
        assertThat(docs.withExpiry().single().expiresAt).isEqualTo(at)
        assertThat(docs.get("a")!!.expiryNotifiedDays).isEqualTo(90)
        docs.setExpiryNotified("a", 30)
        assertThat(docs.get("a")!!.expiryNotifiedDays).isEqualTo(30)
        docs.setExpiry("a", null, null)
        assertThat(docs.withExpiry()).isEmpty()
    }

    @Test fun pinnedFoldersAreStored() = runTest {
        val folders = RoomFolderRepository(db)
        folders.create(Folder("f", "Bills", null, clock.now(), clock.now()))
        folders.setPinned(listOf("f"), true)
        assertThat(folders.get("f")!!.pinned).isTrue()
    }
}

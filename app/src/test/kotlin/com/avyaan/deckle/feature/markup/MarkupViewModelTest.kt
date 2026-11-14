package com.avyaan.deckle.feature.markup

import androidx.lifecycle.SavedStateHandle
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.markup.MarkupIds
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.SaveMarkupUseCase
import com.avyaan.deckle.domain.markup.SaveSignatureUseCase
import com.avyaan.deckle.domain.markup.SavedSignature
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.markup.SignatureStore
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.page.Page
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NV-40…44 editor: undo/redo, moving marks, signing, saving and surviving process death. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val pages = FakePageRepository()
    private val docs = FakeDocumentRepository(pages)
    private val store = object : SignatureStore {
        val saved = mutableListOf<SavedSignature>()
        override suspend fun list() = saved.toList()
        override suspend fun save(ink: SignatureInk) = SavedSignature("sig-${saved.size + 1}", ink).also { saved += it }
        override suspend fun delete(id: String) { saved.removeAll { it.id == id } }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.editor(saved: SavedStateHandle = SavedStateHandle(mapOf("pageId" to "p"))): MarkupViewModel {
        if (pages.get("p") == null) {
            docs.create("d", "Lease", FakeClock().now())
            pages.insert(
                listOf(
                    Page(
                        "p", "d", 0, null, "/b", "/p", "/t", Quad.FULL, Rotation.R0, EnhancementMode.COLOUR, Adjustments.NEUTRAL,
                        1000, 1400, OcrState.QUEUED,
                    ),
                ),
            )
        }
        val vm = MarkupViewModel(
            savedState = saved,
            pages = pages,
            saveMarkup = SaveMarkupUseCase(pages, docs, FakeClock()),
            saveSignature = SaveSignatureUseCase(store),
            signatures = store,
            ids = MarkupIds(SequentialIds("m")),
            settings = FakeSettingsRepository(),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun ink(id: String) = MarkupItem.Ink(id, listOf(0.1f, 0.1f, 0.3f, 0.3f), MarkupItem.INK_BLUE, 0.004f)

    @Test fun `marks can be undone and redone`() = runTest(dispatcher) {
        val vm = editor()
        vm.onEvent(MarkupEvent.Add(ink("a")))
        vm.onEvent(MarkupEvent.Add(ink("b")))
        assertThat(vm.state.value.markup.items.map { it.id }).containsExactly("a", "b").inOrder()

        vm.onEvent(MarkupEvent.Undo)
        assertThat(vm.state.value.markup.items.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.canRedo).isTrue()
        vm.onEvent(MarkupEvent.Redo)
        assertThat(vm.state.value.markup.items.map { it.id }).containsExactly("a", "b").inOrder()

        vm.onEvent(MarkupEvent.Undo)
        vm.onEvent(MarkupEvent.Add(ink("c")))                               // a new mark drops the redo history
        assertThat(vm.state.value.canRedo).isFalse()
    }

    @Test fun `drawing a signature saves it encrypted, places it selected, and it can be moved`() = runTest(dispatcher) {
        val vm = editor()
        vm.onEvent(MarkupEvent.SaveSignature(listOf(listOf(10f, 40f, 210f, 40f), listOf(60f, 20f, 60f, 80f))))

        assertThat(store.saved).hasSize(1)
        assertThat(vm.state.value.signatures).hasSize(1)
        val placed = vm.state.value.markup.items.single() as MarkupItem.Signature
        assertThat(vm.state.value.selectedId).isEqualTo(placed.id)
        assertThat(vm.state.value.tool).isEqualTo(MarkupTool.SELECT)

        vm.onEvent(MarkupEvent.Transform(placed.id, dx = 0.1f, dy = -0.2f, zoom = 1.5f, rotation = 10f))
        val moved = vm.state.value.markup.items.single() as MarkupItem.Signature
        assertThat(moved.cx).isWithin(1e-5f).of(placed.cx + 0.1f)
        assertThat(moved.cy).isWithin(1e-5f).of(placed.cy - 0.2f)
        assertThat(moved.width).isWithin(1e-5f).of(placed.width * 1.5f)
        assertThat(moved.rotation).isEqualTo(10f)

        vm.onEvent(MarkupEvent.DeleteSelected)
        assertThat(vm.state.value.markup.isEmpty).isTrue()
    }

    @Test fun `text can be edited, and emptying it removes it`() = runTest(dispatcher) {
        val vm = editor()
        vm.onEvent(MarkupEvent.Add(MarkupItem.Text("t", 0.2f, 0.3f, "Jai", 0.03f, MarkupItem.INK_BLACK)))
        vm.onEvent(MarkupEvent.EditText("t", "Jai Sharma"))
        assertThat((vm.state.value.markup.items.single() as MarkupItem.Text).text).isEqualTo("Jai Sharma")
        vm.onEvent(MarkupEvent.EditText("t", "  "))
        assertThat(vm.state.value.markup.items).isEmpty()
    }

    @Test fun `done stores the marks on the page and closes`() = runTest(dispatcher) {
        val vm = editor()
        vm.onEvent(MarkupEvent.Add(MarkupItem.Redaction("r", 0.1f, 0.1f, 0.5f, 0.2f)))
        val effects = mutableListOf<MarkupEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onEvent(MarkupEvent.Save)
        advanceUntilIdle()
        assertThat(pages.get("p")!!.markup!!.redactions).hasSize(1)
        assertThat(effects).contains(MarkupEffect.Close)
    }

    @Test fun `work in progress survives process death, including erasing everything`() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("pageId" to "p"))
        val first = editor(saved)
        first.onEvent(MarkupEvent.Add(ink("a")))
        first.onEvent(MarkupEvent.Save)
        advanceUntilIdle()
        assertThat(pages.get("p")!!.markup!!.items).hasSize(1)

        // Second session: erase the mark, then the process dies before Done.
        val again = SavedStateHandle(mapOf("pageId" to "p"))
        val second = editor(again)
        assertThat(second.state.value.markup.items).hasSize(1)
        second.onEvent(MarkupEvent.Select("a"))
        second.onEvent(MarkupEvent.DeleteSelected)
        val restored = editor(again)                                         // same SavedStateHandle, new ViewModel
        assertThat(restored.state.value.markup).isEqualTo(PageMarkup.EMPTY)
        assertThat(restored.state.value.dirty).isTrue()
        assertThat(pages.get("p")!!.markup!!.items).hasSize(1)               // nothing saved until Done
    }

}

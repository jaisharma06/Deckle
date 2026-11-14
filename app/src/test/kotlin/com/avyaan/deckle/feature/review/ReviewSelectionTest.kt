package com.avyaan.deckle.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.fakes.FakeClock
import com.avyaan.deckle.domain.fakes.FakeDocumentRepository
import com.avyaan.deckle.domain.fakes.FakeFileStore
import com.avyaan.deckle.domain.fakes.FakeOcrScheduler
import com.avyaan.deckle.domain.fakes.FakePageRepository
import com.avyaan.deckle.domain.fakes.FakeScanSessionStore
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.FakeThumbnailMaker
import com.avyaan.deckle.domain.fakes.FakeTransactionRunner
import com.avyaan.deckle.domain.fakes.NoopLogger
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.BlurPolicy
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.ImageRotator
import com.avyaan.deckle.domain.imaging.LumaFrame
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.scan.CommitScanSessionUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.EditCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessingQueue
import com.avyaan.deckle.domain.scan.RemoveCaptureUseCase
import com.avyaan.deckle.domain.scan.ResumeProcessingUseCase
import com.avyaan.deckle.domain.scan.StagedCapture
import com.avyaan.deckle.domain.scan.StagedStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
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

/** NV-01: select several pages of a fresh scan and delete, rotate or re-filter them together. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewSelectionTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
        override val imaging = dispatcher
        override val preview = dispatcher
    }
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val files = FakeFileStore()
    private val ids = SequentialIds()
    private val settings = FakeSettingsRepository()
    private val sessions = FakeScanSessionStore()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun capture(id: String) = StagedCapture(
        id = id, sourcePath = "s/$id", previewPath = "p/$id", basePath = "b/$id", processedPath = "o/$id",
        status = StagedStatus.READY, awaitingNormalize = false,
        adjustments = Adjustments(brightness = 0.1f, contrast = 0f),
    )

    private suspend fun TestScope.review(owner: ViewModelStore = ViewModelStore()): ReviewViewModel {
        sessions.create("s1", 0, null, null)
        sessions.update("s1") { it.copy(captures = listOf(capture("c1"), capture("c2"), capture("c3"))) }
        val queue = ProcessingQueue(
            ProcessCaptureUseCase(sessions, files, Unused, Unused, Unused, Unused, ids, NoopLogger, { null }, { _, _, _ -> error("unused") }) { error("unused") },
            backgroundScope, dispatchers, NoopLogger,
        )
        val pages = FakePageRepository()
        val docs = FakeDocumentRepository(pages)
        val vm = ViewModelProvider.create(owner, viewModelFactory {
            initializer {
                ReviewViewModel(
                    savedState = SavedStateHandle(mapOf("sessionId" to "s1", "captureId" to "c1")),
                    sessions = sessions,
                    edit = EditCaptureUseCase(sessions, queue),
                    removeCapture = RemoveCaptureUseCase(sessions, files),
                    discard = DiscardScanSessionUseCase(sessions),
                    commit = CommitScanSessionUseCase(sessions, docs, pages, files, FakeThumbnailMaker(files), FakeTransactionRunner(), FakeOcrScheduler(), settings, ids, FakeClock(), NoComposer),
                    enhancer = Unused,
                    files = files,
                    resumeProcessing = ResumeProcessingUseCase(sessions, files, queue),
                    settings = settings,
                    appScope = appScope,
                )
            }
        })[ReviewViewModel::class]
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private suspend fun captures() = sessions.get("s1")?.captures.orEmpty()

    private fun ReviewViewModel.select(vararg ids: String) {
        onEvent(ReviewEvent.SetSelecting(true))
        ids.forEach { onEvent(ReviewEvent.ToggleSelect(it)) }
    }

    @Test
    fun `selecting toggles pages and select all picks every page`() = runTest(dispatcher) {
        val vm = review()
        vm.select("c1", "c3", "c1")
        assertThat(vm.state.value.selecting).isTrue()
        assertThat(vm.state.value.selection).containsExactly("c3")

        vm.onEvent(ReviewEvent.SelectAll)
        assertThat(vm.state.value.selection).containsExactly("c1", "c2", "c3")

        vm.onEvent(ReviewEvent.SetSelecting(false))
        assertThat(vm.state.value.selecting).isFalse()
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test
    fun `deleting the selection hides those pages, leaves selection mode, and is written on confirm`() = runTest(dispatcher) {
        val vm = review()
        val effects = mutableListOf<ReviewEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.select("c1", "c3")

        vm.onEvent(ReviewEvent.DeleteSelected)
        assertThat(vm.state.value.pages.map { it.id }).containsExactly("c2")
        assertThat(vm.state.value.selecting).isFalse()
        val deleted = effects.filterIsInstance<ReviewEffect.PagesDeleted>().single()
        assertThat(deleted.captureIds).containsExactly("c1", "c3")

        vm.onEvent(ReviewEvent.ConfirmDelete(deleted.captureIds))
        advanceUntilIdle()
        assertThat(captures().map { it.id }).containsExactly("c2")
    }

    @Test
    fun `undo brings every page of a multi-delete back`() = runTest(dispatcher) {
        val vm = review()
        vm.select("c1", "c3")
        vm.onEvent(ReviewEvent.DeleteSelected)
        vm.onEvent(ReviewEvent.UndoDelete(listOf("c1", "c3")))
        assertThat(vm.state.value.pages.map { it.id }).containsExactly("c1", "c2", "c3").inOrder()
    }

    @Test
    fun `deleting every page discards the scan`() = runTest(dispatcher) {
        val vm = review()
        val effects = mutableListOf<ReviewEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.onEvent(ReviewEvent.SetSelecting(true))
        vm.onEvent(ReviewEvent.SelectAll)
        vm.onEvent(ReviewEvent.DeleteSelected)
        vm.onEvent(ReviewEvent.ConfirmDelete(listOf("c1", "c2", "c3")))
        advanceUntilIdle()
        assertThat(effects).contains(ReviewEffect.Discarded)
    }

    @Test
    fun `rotate turns only the selected pages`() = runTest(dispatcher) {
        val vm = review()
        vm.select("c1", "c2")
        vm.onEvent(ReviewEvent.RotateSelected)
        advanceUntilIdle()
        assertThat(captures().associate { it.id to it.rotation })
            .containsExactly("c1", Rotation.R90, "c2", Rotation.R90, "c3", Rotation.R0)
        assertThat(vm.state.value.selecting).isTrue()           // stays in selection for the next action
    }

    @Test
    fun `a filter applies to the selected pages and keeps their own adjustments`() = runTest(dispatcher) {
        val vm = review()
        vm.select("c2", "c3")
        vm.onEvent(ReviewEvent.FilterSelected(EnhancementMode.BLACK_AND_WHITE))
        advanceUntilIdle()
        val byId = captures().associateBy { it.id }
        assertThat(byId.getValue("c1").mode).isEqualTo(EnhancementMode.COLOUR)
        assertThat(byId.getValue("c2").mode).isEqualTo(EnhancementMode.BLACK_AND_WHITE)
        assertThat(byId.getValue("c3").mode).isEqualTo(EnhancementMode.BLACK_AND_WHITE)
        assertThat(byId.getValue("c3").adjustments.brightness).isEqualTo(0.1f)
    }

    // ---- NV-10 blur banner ----

    @Test
    fun `a blurry page shows the banner until the user keeps it`() = runTest(dispatcher) {
        val vm = review()
        sessions.update("s1") { it.updateCapture("c1") { c -> c.copy(sharpness = BlurPolicy.BLURRY_BELOW / 2) } }
        assertThat(vm.state.value.current?.id).isEqualTo("c1")
        assertThat(vm.state.value.current?.blurry).isTrue()
        assertThat(vm.state.value.pages.single { it.id == "c2" }.blurry).isFalse()

        vm.onEvent(ReviewEvent.KeepBlurry)
        advanceUntilIdle()
        assertThat(vm.state.value.current?.blurry).isFalse()
    }

    @Test
    fun `retake removes the blurry page and opens the camera on the same scan`() = runTest(dispatcher) {
        val vm = review()
        val effects = mutableListOf<ReviewEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        sessions.update("s1") { it.updateCapture("c1") { c -> c.copy(sharpness = BlurPolicy.BLURRY_BELOW / 2) } }

        vm.onEvent(ReviewEvent.RetakeBlurry)
        advanceUntilIdle()
        assertThat(captures().map { it.id }).containsExactly("c2", "c3").inOrder()
        assertThat(effects).contains(ReviewEffect.OpenCapture("s1"))
    }

    private object Unused : PageEnhancer, ImageRotator, ImageNormalizer, PageDetector, PerspectiveCorrector {
        override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String) = error("unused")
        override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String) =
            Outcome.Failure(DeckleError.ImageDecodeFailed)
        override suspend fun ocrInput(basePath: String, out: String) = error("unused")
        override suspend fun rotate(path: String, rotation: Rotation, out: String) = error("unused")
        override suspend fun normalize(input: ImageInput, sourceOut: String, previewOut: String, previewLongEdge: Int) = error("unused")
        override val isAvailable = false
        override fun detect(frame: LumaFrame): Quad? = null
        override suspend fun detect(imagePath: String): Quad? = null
        override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String) = error("unused")
    }

    private object NoComposer : com.avyaan.deckle.domain.imaging.CardComposer {
        override suspend fun compose(frontPath: String, backPath: String?, out: String) = error("unused")
    }
}

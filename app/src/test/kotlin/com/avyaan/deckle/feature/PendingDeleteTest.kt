package com.avyaan.deckle.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageRotator
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.ocr.OcrState
import com.avyaan.deckle.domain.page.DeletePageUseCase
import com.avyaan.deckle.domain.page.ObservePagesUseCase
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageRenderer
import com.avyaan.deckle.domain.page.ReEnhancePageUseCase
import com.avyaan.deckle.domain.page.RenamePageUseCase
import com.avyaan.deckle.domain.page.RotatePageUseCase
import com.avyaan.deckle.domain.scan.CommitScanSessionUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.EditCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessingQueue
import com.avyaan.deckle.domain.scan.RemoveCaptureUseCase
import com.avyaan.deckle.domain.scan.ResumeProcessingUseCase
import com.avyaan.deckle.domain.scan.StagedCapture
import com.avyaan.deckle.domain.scan.StagedStatus
import com.avyaan.deckle.feature.review.ReviewEvent
import com.avyaan.deckle.feature.review.ReviewViewModel
import com.avyaan.deckle.feature.viewer.ViewerEvent
import com.avyaan.deckle.feature.viewer.ViewerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/**
 * A page deleted in Review or the Viewer must stay deleted even if the screen is left before its Undo
 * snackbar times out: Back, Add page or Crop used to cancel the snackbar, so the delete was never written
 * and the page came back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingDeleteTest {

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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private inline fun <reified T : ViewModel> ViewModelStore.create(crossinline make: () -> T): T =
        ViewModelProvider.create(this, viewModelFactory { initializer { make() } })[T::class]

    // ---- Review (scan session) ----

    private val sessions = FakeScanSessionStore()

    private fun capture(id: String) = StagedCapture(
        id = id, sourcePath = "s/$id", previewPath = "p/$id", basePath = "b/$id", processedPath = "o/$id",
        status = StagedStatus.READY, awaitingNormalize = false,
    )

    private suspend fun TestScope.review(owner: ViewModelStore): ReviewViewModel {
        sessions.create("s1", 0, null, null)
        sessions.update("s1") { it.copy(captures = listOf(capture("c1"), capture("c2"))) }
        val queue = ProcessingQueue(
            ProcessCaptureUseCase(sessions, files, Unused, Unused, Unused, Unused, ids, NoopLogger) { error("unused") },
            backgroundScope, dispatchers, NoopLogger,
        )
        val pages = FakePageRepository()
        val docs = FakeDocumentRepository(pages)
        val vm = owner.create {
            ReviewViewModel(
                savedState = SavedStateHandle(mapOf("sessionId" to "s1", "captureId" to "c2")),
                sessions = sessions,
                edit = EditCaptureUseCase(sessions, queue),
                removeCapture = RemoveCaptureUseCase(sessions, files),
                discard = DiscardScanSessionUseCase(sessions),
                commit = CommitScanSessionUseCase(sessions, docs, pages, files, FakeThumbnailMaker(files), FakeTransactionRunner(), FakeOcrScheduler(), settings, ids, FakeClock()),
                enhancer = Unused,
                files = files,
                resumeProcessing = ResumeProcessingUseCase(sessions, files, queue),
                settings = settings,
                appScope = appScope,
            )
        }
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private suspend fun captureIds() = sessions.get("s1")?.captures?.map { it.id }

    @Test
    fun `review - a deleted page stays deleted when the screen closes before the snackbar does`() = runTest(dispatcher) {
        val owner = ViewModelStore()
        val vm = review(owner)
        vm.onEvent(ReviewEvent.DeletePage)
        owner.clear()                    // Back / Add page: no Undo or ConfirmDelete ever arrives
        advanceUntilIdle()
        assertThat(captureIds()).containsExactly("c1")
    }

    @Test
    fun `review - undo still restores the page`() = runTest(dispatcher) {
        val owner = ViewModelStore()
        val vm = review(owner)
        vm.onEvent(ReviewEvent.DeletePage)
        vm.onEvent(ReviewEvent.UndoDelete("c2"))
        owner.clear()
        advanceUntilIdle()
        assertThat(captureIds()).containsExactly("c1", "c2").inOrder()
    }

    // ---- Viewer (saved document) ----

    private val pageRepo = FakePageRepository()

    private fun page(id: String, order: Long) = Page(
        id = id, documentId = "d1", orderIndex = order, sourcePath = null, basePath = "b/$id", processedPath = "o/$id",
        thumbPath = "t/$id", corners = Quad.FULL, rotation = Rotation.R0, mode = EnhancementMode.COLOUR,
        adjustments = Adjustments.NEUTRAL, widthPx = 100, heightPx = 100, ocr = OcrState.QUEUED,
    )

    private suspend fun TestScope.viewer(owner: ViewModelStore): ViewerViewModel {
        pageRepo.insert(listOf(page("p1", 0), page("p2", 1)))
        val docs = FakeDocumentRepository(pageRepo)
        val clock = FakeClock()
        val renderer = PageRenderer(files, Unused, FakeThumbnailMaker(files), ids)
        val vm = owner.create {
            ViewerViewModel(
                savedState = SavedStateHandle(mapOf("documentId" to "d1", "pageId" to "p2")),
                observePages = ObservePagesUseCase(pageRepo),
                settings = settings,
                reEnhance = ReEnhancePageUseCase(pageRepo, renderer, docs, clock),
                rotate = RotatePageUseCase(pageRepo, Unused, FakeThumbnailMaker(files), renderer, docs, ids, clock),
                deletePage = DeletePageUseCase(pageRepo, docs, files, FakeOcrScheduler(), FakeTransactionRunner(), clock, com.avyaan.deckle.domain.document.DeleteDocumentsPermanentlyUseCase(docs, files, FakeTransactionRunner())),
                enhancer = Unused,
                files = files,
                renamePage = RenamePageUseCase(pageRepo, docs, clock),
                appScope = appScope,
            )
        }
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `viewer - a deleted page stays deleted when the screen closes before the snackbar does`() = runTest(dispatcher) {
        val owner = ViewModelStore()
        val vm = viewer(owner)
        vm.onEvent(ViewerEvent.Delete)
        owner.clear()
        advanceUntilIdle()
        assertThat(pageRepo.all().map { it.id }).containsExactly("p1")
    }

    @Test
    fun `viewer - undo still restores the page`() = runTest(dispatcher) {
        val owner = ViewModelStore()
        val vm = viewer(owner)
        vm.onEvent(ViewerEvent.Delete)
        vm.onEvent(ViewerEvent.UndoDelete("p2"))
        owner.clear()
        advanceUntilIdle()
        assertThat(pageRepo.all().map { it.id }).containsExactly("p1", "p2")
    }

    private object Unused : PageEnhancer, ImageRotator,
        com.avyaan.deckle.domain.imaging.ImageNormalizer, com.avyaan.deckle.domain.imaging.PageDetector,
        com.avyaan.deckle.domain.imaging.PerspectiveCorrector {
        override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String) = error("unused")
        override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String) =
            Outcome.Failure(com.avyaan.deckle.domain.common.DeckleError.ImageDecodeFailed)
        override suspend fun ocrInput(basePath: String, out: String) = error("unused")
        override suspend fun rotate(path: String, rotation: Rotation, out: String) = error("unused")
        override suspend fun normalize(input: com.avyaan.deckle.domain.imaging.ImageInput, sourceOut: String, previewOut: String, previewLongEdge: Int) = error("unused")
        override val isAvailable = false
        override fun detect(frame: com.avyaan.deckle.domain.imaging.LumaFrame): Quad? = null
        override suspend fun detect(imagePath: String): Quad? = null
        override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String) = error("unused")
    }
}

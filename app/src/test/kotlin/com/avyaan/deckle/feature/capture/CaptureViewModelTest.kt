package com.avyaan.deckle.feature.capture

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.MonotonicClock
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.fakes.FakeFileStore
import com.avyaan.deckle.domain.fakes.FakeScanSessionStore
import com.avyaan.deckle.domain.fakes.FakeSettingsRepository
import com.avyaan.deckle.domain.fakes.NoopLogger
import com.avyaan.deckle.domain.fakes.SequentialIds
import com.avyaan.deckle.domain.geometry.ImageSize
import com.avyaan.deckle.domain.geometry.PointN
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.LumaFrame
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.scan.AddCaptureUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.ProcessCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessingQueue
import com.avyaan.deckle.domain.scan.StartScanSessionUseCase
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.feature.capture.camera.DetectionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
import com.avyaan.deckle.domain.fakes.FakeClock

/**
 * The capture ViewModel never touches CameraX (spec 06 §2), so its logic runs here on the JVM with a
 * fake monotonic clock. Robolectric is only needed for `SavedStateHandle.toRoute`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 10_000L
    private val clock = MonotonicClock { now }
    private val store = FakeScanSessionStore()
    private val files = FakeFileStore()

    /** A plausible page: well inside the frame, covering about half of it. */
    private val page = Quad(PointN(0.15f, 0.15f), PointN(0.85f, 0.15f), PointN(0.85f, 0.85f), PointN(0.15f, 0.85f))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(settings: AppSettings = AppSettings()): CaptureViewModel {
        val ids = SequentialIds()
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val imaging = dispatcher
            override val preview = dispatcher
        }
        lateinit var queue: ProcessingQueue
        val process = ProcessCaptureUseCase(store, files, FailingNormalizer, NoDetector, Unused, Unused, ids, NoopLogger) { queue }
        queue = ProcessingQueue(process, backgroundScope, dispatchers, NoopLogger)
        val settingsRepo = FakeSettingsRepository(settings)
        return CaptureViewModel(
            savedState = SavedStateHandle(),
            detector = NoDetector,
            clock = clock,
            settingsRepo = settingsRepo,
            startSession = StartScanSessionUseCase(store, ids, FakeClock()),
            addCapture = AddCaptureUseCase(store, files, settingsRepo, queue, ids),
            discardSession = DiscardScanSessionUseCase(store),
            sessions = store,
        )
    }

    private fun CaptureViewModel.detect(quad: Quad?) = onEvent(CaptureEvent.Detection(DetectionResult(quad, 480, 640)))

    @Test
    fun `auto-capture fires once the page has been held steady`() = runTest(dispatcher) {
        val vm = viewModel(AppSettings(autoCapture = true))
        advanceUntilIdle()
        vm.effects.test {
            repeat(5) { vm.detect(page); now += 50 }     // fills the stability window
            expectNoEvents()
            now += 1_300                                 // past the 1.2 s hold
            vm.detect(page)
            assertThat(awaitItem()).isEqualTo(CaptureEffect.TakePicture)
            assertThat(vm.state.value.isCapturing).isTrue()
        }
    }

    @Test
    fun `without auto-capture a steady page only becomes ready`() = runTest(dispatcher) {
        val vm = viewModel(AppSettings(autoCapture = false))
        advanceUntilIdle()
        vm.effects.test {
            repeat(5) { vm.detect(page); now += 50 }
            now += 5_000
            vm.detect(page)
            expectNoEvents()
            assertThat(vm.state.value.hint).isEqualTo(CaptureHint.READY)
        }
    }

    @Test
    fun `a double tap on the shutter takes one picture`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.effects.test {
            vm.onEvent(CaptureEvent.ShutterPressed)
            now += 100
            vm.onEvent(CaptureEvent.ShutterPressed)
            assertThat(awaitItem()).isEqualTo(CaptureEffect.TakePicture)
            expectNoEvents()
        }
    }

    @Test
    fun `closing a new, empty session discards it and exits`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val sessionId = vm.state.value.sessionId!!
        vm.effects.test {
            vm.onEvent(CaptureEvent.Close)
            assertThat(awaitItem()).isEqualTo(CaptureEffect.Exit)
        }
        assertThat(store.get(sessionId)).isNull()
    }

    private object NoDetector : PageDetector {
        override val isAvailable = true
        override fun detect(frame: LumaFrame): Quad? = null
        override suspend fun detect(imagePath: String): Quad? = null
    }

    private object FailingNormalizer : ImageNormalizer {
        override suspend fun normalize(input: ImageInput, sourceOut: String, previewOut: String, previewLongEdge: Int): Outcome<ImageSize> =
            Outcome.Failure(DeckleError.ImageDecodeFailed)
    }

    private object Unused : PerspectiveCorrector, PageEnhancer {
        override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String) = error("unused")
        override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String) = error("unused")
        override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String) = error("unused")
        override suspend fun ocrInput(basePath: String, out: String) = error("unused")
    }
}

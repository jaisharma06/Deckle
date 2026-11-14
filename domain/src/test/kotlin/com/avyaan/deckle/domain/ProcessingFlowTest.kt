package com.avyaan.deckle.domain

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.DispatcherProvider
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.fakes.FakeClock
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
import com.avyaan.deckle.domain.scan.AddCaptureUseCase
import com.avyaan.deckle.domain.scan.EditCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessCaptureUseCase
import com.avyaan.deckle.domain.scan.ProcessingQueue
import com.avyaan.deckle.domain.scan.ResumeProcessingUseCase
import com.avyaan.deckle.domain.scan.StagedStatus
import com.avyaan.deckle.domain.scan.StartScanSessionUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.avyaan.deckle.domain.imaging.BookSplitter
import com.avyaan.deckle.domain.scan.CaptureKind
import com.avyaan.deckle.domain.imaging.BlurPolicy
import org.junit.jupiter.api.Test

/**
 * The single-capture flow end to end through the real [ProcessingQueue]:
 * shutter → crop (while the still is still being normalised) → Review opens → page renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingFlowTest {

    private val store = FakeScanSessionStore()
    private val files = FakeFileStore()
    private val corrector = RecordingCorrector(files)
    private lateinit var normalizer: GatedNormalizer
    /** Normalisation blocks until released, so Review can open while it is in flight. */
    private val normalizeGate = CompletableDeferred<Unit>()

    private val userCrop = Quad(PointN(0.2f, 0.1f), PointN(0.8f, 0.1f), PointN(0.8f, 0.9f), PointN(0.2f, 0.9f))

    /** Writes both halves, like the real splitter; records what it was given. */
    private val splits = mutableListOf<String>()
    private val books = BookSplitter { spread, left, right ->
        splits += spread
        files.put(left); files.put(right)
        Outcome.Success(Unit)
    }

    /** What the sharpness meter reports for the next render. */
    private var sharpnessScore: Float? = null

    private fun TestScope.wire(): Wiring {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val imaging = dispatcher
            override val preview = dispatcher
        }
        val ids = SequentialIds()
        lateinit var queue: ProcessingQueue
        val process = ProcessCaptureUseCase(store, files, GatedNormalizer(files, normalizeGate).also { normalizer = it }, NoDetector, corrector, CopyEnhancer(files), ids, NoopLogger, { sharpnessScore }, books) { queue }
        queue = ProcessingQueue(process, backgroundScope, dispatchers, NoopLogger)
        return Wiring(
            process = process,
            start = StartScanSessionUseCase(store, ids, FakeClock()),
            add = AddCaptureUseCase(store, files, FakeSettingsRepository(), queue, ids),
            edit = EditCaptureUseCase(store, queue),
            resume = ResumeProcessingUseCase(store, files, queue),
        )
    }

    @Test
    fun `cropping, then opening Review while the still is normalising, renders the cropped page`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/shot.jpg")
        val captureId = w.add(session, ImageInput.FilePath("/cache/capture/shot.jpg"), liveQuad = null)
        runCurrent()                                     // normalise has started and is blocked

        val statuses = mutableListOf<StagedStatus>()
        backgroundScope.launch { store.observe(session).collect { s -> s?.capture(captureId)?.status?.let(statuses::add) } }

        w.edit.setCrop(session, captureId, userCrop, Rotation.R0)   // user confirms the crop
        w.resume(session)                                            // Review opens and re-queues unfinished work
        runCurrent()

        normalizeGate.complete(Unit)
        runCurrent()                                     // background work (the queue) only runs via runCurrent

        val capture = store.get(session)!!.capture(captureId)!!
        // Review re-queuing work must not normalise the same capture twice: the second run finds the raw
        // file already consumed and marks the page FAILED ("This page couldn't be processed").
        assertThat(normalizer.calls).isEqualTo(1)
        assertThat(statuses).doesNotContain(StagedStatus.FAILED)
        assertThat(capture.status).isEqualTo(StagedStatus.READY)
        assertThat(corrector.quads.last()).isEqualTo(userCrop)
    }

    @Test
    fun `a blurry render is flagged, a sharp one isn't, and keeping it clears the warning`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/shot.jpg")
        sharpnessScore = BlurPolicy.BLURRY_BELOW / 2
        val captureId = w.add(session, ImageInput.FilePath("/cache/capture/shot.jpg"), liveQuad = null)
        normalizeGate.complete(Unit)
        runCurrent()
        assertThat(store.get(session)!!.capture(captureId)!!.looksBlurry).isTrue()

        w.edit.acceptBlur(session, captureId)
        assertThat(store.get(session)!!.capture(captureId)!!.looksBlurry).isFalse()

        // A re-crop re-measures; a sharp result is never flagged.
        sharpnessScore = BlurPolicy.BLURRY_BELOW * 2
        w.edit.setCrop(session, captureId, userCrop, Rotation.R0)
        runCurrent()
        assertThat(store.get(session)!!.capture(captureId)!!.sharpness).isEqualTo(BlurPolicy.BLURRY_BELOW * 2)
    }

    @Test
    fun `a page the meter can't judge is never called blurry`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/shot.jpg")
        sharpnessScore = null
        val captureId = w.add(session, ImageInput.FilePath("/cache/capture/shot.jpg"), liveQuad = null)
        normalizeGate.complete(Unit)
        runCurrent()
        assertThat(store.get(session)!!.capture(captureId)!!.looksBlurry).isFalse()
    }

    @Test
    fun `a book spread becomes two pages in its place, each processed on its own`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/a.jpg"); files.put("/cache/capture/book.jpg"); files.put("/cache/capture/z.jpg")
        val before = w.add(session, ImageInput.FilePath("/cache/capture/a.jpg"), liveQuad = null)
        val spread = w.add(session, ImageInput.FilePath("/cache/capture/book.jpg"), liveQuad = null, kind = CaptureKind.BOOK_SPREAD)
        val after = w.add(session, ImageInput.FilePath("/cache/capture/z.jpg"), liveQuad = null)
        normalizeGate.complete(Unit)
        runCurrent()

        val captures = store.get(session)!!.captures
        assertThat(captures).hasSize(4)
        assertThat(captures.first().id).isEqualTo(before)
        assertThat(captures.last().id).isEqualTo(after)
        val halves = captures.subList(1, 3)
        assertThat(halves.map { it.id }).doesNotContain(spread)
        assertThat(halves.all { it.kind == CaptureKind.PAGE && it.status == StagedStatus.READY }).isTrue()
        assertThat(halves.all { files.exists(it.sourcePath) && it.basePath != null }).isTrue()
        assertThat(splits).hasSize(1)
        assertThat(files.files.keys.none { it.contains("/$spread") }).isTrue()   // the spread's own files are gone
    }

    @Test
    fun `a render re-queued while one is in flight does not redo the page`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/shot.jpg")
        val captureId = w.add(session, ImageInput.FilePath("/cache/capture/shot.jpg"), liveQuad = null)
        normalizeGate.complete(Unit)
        runCurrent()                                     // normalised and rendered once: READY
        assertThat(store.get(session)!!.capture(captureId)!!.status).isEqualTo(StagedStatus.READY)
        val base = store.get(session)!!.capture(captureId)!!.basePath

        // Review's resume saw PROCESSING while that render ran, so a second Render job reaches the queue.
        w.process.render(session, captureId)

        assertThat(corrector.quads).hasSize(1)           // the full-resolution work is not repeated
        assertThat(store.get(session)!!.capture(captureId)!!.basePath).isEqualTo(base)   // nothing superseded
    }

    @Test
    fun `an edit after the page is ready still re-renders`() = runTest {
        val w = wire()
        val session = w.start(null, null, null).id
        files.put("/cache/capture/shot.jpg")
        val captureId = w.add(session, ImageInput.FilePath("/cache/capture/shot.jpg"), liveQuad = null)
        normalizeGate.complete(Unit)
        runCurrent()

        w.edit.setCrop(session, captureId, userCrop, Rotation.R0)
        runCurrent()

        assertThat(corrector.quads).containsExactly(Quad.FULL, userCrop).inOrder()
        assertThat(store.get(session)!!.capture(captureId)!!.status).isEqualTo(StagedStatus.READY)
    }

    private class Wiring(
        val process: ProcessCaptureUseCase,
        val start: StartScanSessionUseCase,
        val add: AddCaptureUseCase,
        val edit: EditCaptureUseCase,
        val resume: ResumeProcessingUseCase,
    )

    private class GatedNormalizer(private val files: FakeFileStore, private val gate: CompletableDeferred<Unit>) : ImageNormalizer {
        var calls = 0
        override suspend fun normalize(input: ImageInput, sourceOut: String, previewOut: String, previewLongEdge: Int): Outcome<ImageSize> {
            calls++
            gate.await()
            val path = (input as ImageInput.FilePath).path
            if (!files.exists(path)) return Outcome.Failure(DeckleError.ImageDecodeFailed)
            files.put(sourceOut); files.put(previewOut)
            return Outcome.Success(ImageSize(3000, 4000))
        }
    }

    private object NoDetector : PageDetector {
        override val isAvailable = true
        override fun detect(frame: LumaFrame): Quad? = null
        override suspend fun detect(imagePath: String): Quad? = null
    }

    private class RecordingCorrector(private val files: FakeFileStore) : PerspectiveCorrector {
        val quads = mutableListOf<Quad>()
        override suspend fun correct(sourcePath: String, quad: Quad, rotation: Rotation, out: String): Outcome<ImageSize> {
            if (!files.exists(sourcePath)) return Outcome.Failure(DeckleError.FileMissing(sourcePath))
            quads += quad
            files.put(out)
            return Outcome.Success(ImageSize(1, 1))
        }
    }

    private class CopyEnhancer(private val files: FakeFileStore) : PageEnhancer {
        override suspend fun enhance(basePath: String, mode: EnhancementMode, adjustments: Adjustments, out: String) =
            Outcome.Success(ImageSize(1, 1)).also { files.put(out) }
        override suspend fun preview(basePath: String, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, out: String) =
            Outcome.Success(out).also { files.put(out) }
        override suspend fun ocrInput(basePath: String, out: String) = Outcome.Success(ImageSize(1, 1)).also { files.put(out) }
    }
}

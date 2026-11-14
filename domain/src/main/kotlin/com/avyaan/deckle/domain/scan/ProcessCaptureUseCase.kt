package com.avyaan.deckle.domain.scan

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.common.Logger
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.common.newRevision
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.imaging.BookSplitter
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.imaging.SharpnessMeter
import com.avyaan.deckle.domain.page.PageFileStore
import javax.inject.Inject
import javax.inject.Provider

/**
 * The still lane (spec 03 §6): normalise + detect, then dewarp + enhance.
 * Called only from [ProcessingQueue], so it runs on the single imaging dispatcher.
 */
public class ProcessCaptureUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val files: PageFileStore,
    private val normalizer: ImageNormalizer,
    private val detector: PageDetector,
    private val corrector: PerspectiveCorrector,
    private val enhancer: PageEnhancer,
    private val ids: IdGenerator,
    private val logger: Logger,
    private val sharpness: SharpnessMeter,
    private val books: BookSplitter,
    /** Breaks the queue ↔ use case cycle: rendering is queued after normalising. */
    private val queue: Provider<ProcessingQueue>,
) {
    public suspend fun normalize(sessionId: String, captureId: String, input: ImageInput, liveQuad: Quad?) {
        val capture = store.get(sessionId)?.capture(captureId) ?: return
        // Idempotent: normalising consumes the raw file, so a duplicate job (Review re-queues unfinished
        // work on entry, possibly while the first run is in flight) must be a no-op, not a FAILED page.
        if (!capture.awaitingNormalize) return
        val size = when (val r = normalizer.normalize(input, capture.sourcePath, capture.previewPath)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return fail(sessionId, captureId, "normalize", r.error)
        }
        if (input is ImageInput.FilePath) files.delete(input.path)

        // Still-lane detection; fall back to the live quad (same field of view thanks to the shared ViewPort).
        val detected = detector.detect(capture.previewPath) ?: liveQuad
        store.update(sessionId) { s ->
            s.updateCapture(captureId) {
                it.copy(
                    sourceWidth = size.width,
                    sourceHeight = size.height,
                    detectedQuad = detected,
                    // Respect a crop the user already made on the frozen frame.
                    quad = if (it.quad == (it.detectedQuad ?: Quad.FULL)) detected ?: Quad.FULL else it.quad,
                    awaitingNormalize = false,
                    status = StagedStatus.PROCESSING,
                )
            }
        }
        queue.get().submit(ProcessingQueue.Job.Render(sessionId, captureId))
    }

    public suspend fun render(sessionId: String, captureId: String) {
        val capture = store.get(sessionId)?.capture(captureId) ?: return
        if (capture.awaitingNormalize) return
        // Idempotent: every edit sets the page back to PROCESSING before queuing, so READY here means the
        // last render already reflects the current settings. Without this, Review's resume (which sees
        // PROCESSING while a render is in flight) redoes the full-res work and deletes the base image
        // the filter thumbnails are reading.
        if (capture.status == StagedStatus.READY) return
        val rev = ids.newRevision()
        val base = files.stagingPath(sessionId, captureId, "base-$rev")
        val processed = files.stagingPath(sessionId, captureId, "processed-$rev")

        val corrected = corrector.correct(capture.sourcePath, capture.quad, capture.rotation, base)
        if (corrected is Outcome.Failure) return fail(sessionId, captureId, "correct", corrected.error)
        if (capture.kind == CaptureKind.BOOK_SPREAD) return splitSpread(sessionId, capture, base)
        // Measured on the straightened page, so the table or background around it doesn't count (NV-10).
        // Cheap (a ~1200 px copy), so every render re-measures rather than tracking what changed.
        val score = try {
            sharpness.measure(base)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val size = when (val r = enhancer.enhance(base, capture.mode, capture.adjustments, processed)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { files.delete(base); return fail(sessionId, captureId, "enhance", r.error) }
        }

        var superseded: List<String?> = emptyList()
        store.update(sessionId) { s ->
            s.updateCapture(captureId) { current ->
                superseded = listOf(current.basePath, current.processedPath)
                // If the user changed settings while we rendered, a newer job is queued: stay PROCESSING.
                val stillCurrent = current.quad == capture.quad && current.rotation == capture.rotation &&
                    current.mode == capture.mode && current.adjustments == capture.adjustments
                current.copy(
                    basePath = base,
                    processedPath = processed,
                    widthPx = size.width,
                    heightPx = size.height,
                    revision = current.revision + 1,
                    sharpness = score,
                    status = if (stillCurrent) StagedStatus.READY else StagedStatus.PROCESSING,
                )
            }
        }
        superseded.filterNotNull().forEach { files.delete(it) }
    }

    /**
     * NV-12: the straightened spread becomes two ordinary captures, in its place in the scan, each with the
     * half as its "original" (so it can still be cropped, rotated and filtered on its own).
     */
    private suspend fun splitSpread(sessionId: String, spread: StagedCapture, straightened: String) {
        val halves = listOf(ids.newId(), ids.newId())
        val raws = halves.map { files.stagingPath(sessionId, it, "raw") }
        val split = books.split(straightened, raws[0], raws[1])
        files.delete(straightened)
        if (split is Outcome.Failure) return fail(sessionId, spread.id, "split", split.error)

        val pages = halves.mapIndexed { i, id ->
            val source = files.stagingPath(sessionId, id, "source")
            val preview = files.stagingPath(sessionId, id, "preview")
            val size = when (val r = normalizer.normalize(ImageInput.FilePath(raws[i]), source, preview)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> { raws.forEach { files.delete(it) }; return fail(sessionId, spread.id, "split-normalize", r.error) }
            }
            files.delete(raws[i])
            StagedCapture(
                id = id, sourcePath = source, previewPath = preview, sourceWidth = size.width, sourceHeight = size.height,
                mode = spread.mode, adjustments = spread.adjustments,
                status = StagedStatus.PROCESSING, awaitingNormalize = false,
            )
        }
        store.update(sessionId) { s ->
            s.copy(captures = s.captures.flatMap { if (it.id == spread.id) pages else listOf(it) })
        }
        listOf(spread.sourcePath, spread.previewPath).forEach { files.delete(it) }
        pages.forEach { queue.get().submit(ProcessingQueue.Job.Render(sessionId, it.id)) }
    }

    /** Every failed page says which step failed and why: a FAILED status alone is undiagnosable. */
    private suspend fun fail(sessionId: String, captureId: String, step: String, error: DeckleError) {
        logger.warn(TAG, "Capture $captureId failed at $step: $error", (error as? DeckleError.Unexpected)?.cause)
        markFailed(sessionId, captureId)
    }

    public suspend fun markFailed(sessionId: String, captureId: String) {
        store.update(sessionId) { s -> s.updateCapture(captureId) { it.copy(status = StagedStatus.FAILED, awaitingNormalize = false) } }
    }

    private companion object {
        const val TAG = "ProcessCapture"
    }
}

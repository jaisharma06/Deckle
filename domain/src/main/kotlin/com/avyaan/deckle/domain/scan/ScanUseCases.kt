package com.avyaan.deckle.domain.scan

import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Opens a new scan session, or returns the existing one when resuming. */
public class StartScanSessionUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public suspend operator fun invoke(
        existingId: String?,
        targetDocumentId: String?,
        insertAfterPageId: String?,
        targetFolderId: String? = null,
    ): ScanSession {
        existingId?.let { store.get(it) }?.let { return it }
        val created = store.create(ids.newId(), clock.now().toEpochMilli(), targetDocumentId, insertAfterPageId)
        if (targetFolderId == null) return created
        return store.update(created.id) { it.copy(targetFolderId = targetFolderId) } ?: created
    }
}

/**
 * Adds a captured or imported image to the session and queues it (FR-18: never blocks the camera).
 * Camera bytes are written to staging first, so a capture survives process death.
 */
public class AddCaptureUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val files: PageFileStore,
    private val settings: SettingsRepository,
    private val queue: ProcessingQueue,
    private val ids: IdGenerator,
) {
    public suspend operator fun invoke(
        sessionId: String,
        input: ImageInput,
        liveQuad: Quad?,
        kind: CaptureKind = CaptureKind.PAGE,
        group: String? = null,
    ): String {
        val captureId = ids.newId()
        // Camera output is adopted into staging first, so a capture survives process death (spec 02 §5.2).
        // Content URIs (imports) stay where they are: the picker grants read access for the session.
        val raw = files.stagingPath(sessionId, captureId, "raw")
        val stagedInput = when (input) {
            is ImageInput.JpegBytes -> { files.writeBytes(raw, input.bytes); ImageInput.FilePath(raw) }
            is ImageInput.FilePath -> { files.move(input.path, raw); ImageInput.FilePath(raw) }
            is ImageInput.ContentUri -> input
        }
        val mode = settings.settings.first().defaultMode
        store.update(sessionId) { s ->
            s.copy(
                captures = s.captures + StagedCapture(
                    id = captureId,
                    sourcePath = files.stagingPath(sessionId, captureId, "source"),
                    previewPath = files.stagingPath(sessionId, captureId, "preview"),
                    detectedQuad = liveQuad,
                    quad = liveQuad ?: Quad.FULL,
                    mode = mode,
                    kind = kind,
                    group = group,
                ),
            )
        }
        queue.submit(ProcessingQueue.Job.Normalize(sessionId, captureId, stagedInput, liveQuad))
        return captureId
    }
}

/** User edits in the crop and review screens. Each edit re-queues a render so "Done" is quick. */
public class EditCaptureUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val queue: ProcessingQueue,
) {
    public suspend fun setCrop(sessionId: String, captureId: String, quad: Quad, rotation: Rotation) {
        edit(sessionId, captureId) { it.copy(quad = quad, rotation = rotation) }
    }

    public suspend fun setFilter(sessionId: String, captureId: String, mode: EnhancementMode, adjustments: Adjustments) {
        edit(sessionId, captureId) { it.copy(mode = mode, adjustments = adjustments) }
    }

    public suspend fun rotate(sessionId: String, captureId: String, clockwise: Boolean) {
        edit(sessionId, captureId) { it.copy(rotation = if (clockwise) it.rotation.clockwise() else it.rotation.counterClockwise()) }
    }

    /** "Apply to all" (UX addition D-10). */
    public suspend fun setFilterForAll(sessionId: String, mode: EnhancementMode, adjustments: Adjustments) {
        editMany(sessionId, null) { it.copy(mode = mode, adjustments = adjustments) }
    }

    /**
     * Multi-select in review (NV-01): one filter mode for the chosen pages, in a single session update.
     * Each page keeps its own brightness/contrast adjustments.
     */
    public suspend fun setModeFor(sessionId: String, captureIds: Set<String>, mode: EnhancementMode) {
        editMany(sessionId, captureIds) { it.copy(mode = mode) }
    }

    /** Multi-select in review (NV-01): a quarter turn for each chosen page. */
    public suspend fun rotateAll(sessionId: String, captureIds: Set<String>, clockwise: Boolean) {
        editMany(sessionId, captureIds) { it.copy(rotation = if (clockwise) it.rotation.clockwise() else it.rotation.counterClockwise()) }
    }

    /** NV-10 "Keep": the user saw the blur warning and wants this page anyway. No re-render needed. */
    public suspend fun acceptBlur(sessionId: String, captureId: String) {
        store.update(sessionId) { s -> s.updateCapture(captureId) { it.copy(blurAccepted = true) } }
    }

    /** [ids] null means every page. */
    private suspend fun editMany(sessionId: String, ids: Set<String>?, change: (StagedCapture) -> StagedCapture) {
        if (ids != null && ids.isEmpty()) return
        val updated = store.update(sessionId) { s ->
            s.copy(captures = s.captures.map { if (ids == null || it.id in ids) change(it).copy(status = pendingStatus(it)) else it })
        } ?: return
        updated.captures
            .filter { (ids == null || it.id in ids) && !it.awaitingNormalize }
            .forEach { queue.submit(ProcessingQueue.Job.Render(sessionId, it.id)) }
    }

    private suspend fun edit(sessionId: String, captureId: String, change: (StagedCapture) -> StagedCapture) {
        val updated = store.update(sessionId) { s -> s.updateCapture(captureId) { c -> change(c).copy(status = pendingStatus(c)) } }
        val capture = updated?.capture(captureId) ?: return
        if (!capture.awaitingNormalize) queue.submit(ProcessingQueue.Job.Render(sessionId, captureId))
    }

    private fun pendingStatus(c: StagedCapture) = if (c.status == StagedStatus.FAILED) StagedStatus.FAILED else StagedStatus.PROCESSING
}

/** Retake (FR-25) or delete a page from an uncommitted scan. */
public class RemoveCaptureUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val files: PageFileStore,
) {
    public suspend operator fun invoke(sessionId: String, captureId: String) {
        var removed: StagedCapture? = null
        store.update(sessionId) { s ->
            removed = s.capture(captureId)
            s.copy(captures = s.captures.filterNot { it.id == captureId })
        }
        removed?.let { c ->
            listOfNotNull(c.sourcePath, c.previewPath, c.basePath, c.processedPath, files.stagingPath(sessionId, c.id, "raw"))
                .forEach { files.delete(it) }
        }
    }
}

/** Re-queues unfinished work after process death (spec 02 §5.2). */
public class ResumeProcessingUseCase @Inject constructor(
    private val store: ScanSessionStore,
    private val files: PageFileStore,
    private val queue: ProcessingQueue,
) {
    public suspend operator fun invoke(sessionId: String) {
        val session = store.get(sessionId) ?: return
        session.captures.forEach { c ->
            when {
                c.awaitingNormalize -> {
                    val raw = files.stagingPath(sessionId, c.id, "raw")
                    if (files.exists(raw)) {
                        queue.submit(ProcessingQueue.Job.Normalize(sessionId, c.id, ImageInput.FilePath(raw), c.detectedQuad))
                    } else {
                        store.update(sessionId) { s -> s.updateCapture(c.id) { it.copy(status = StagedStatus.FAILED) } }
                    }
                }
                c.status != StagedStatus.READY -> queue.submit(ProcessingQueue.Job.Render(sessionId, c.id))
            }
        }
    }
}

public class DiscardScanSessionUseCase @Inject constructor(private val store: ScanSessionStore) {
    public suspend operator fun invoke(sessionId: String): Unit = store.discard(sessionId)
}

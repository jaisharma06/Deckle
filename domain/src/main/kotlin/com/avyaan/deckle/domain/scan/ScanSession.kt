package com.avyaan.deckle.domain.scan

import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * An in-progress scan, living in `files/staging/<id>/` until committed into a document (spec 02 §5).
 * Persisted as JSON so it survives process death.
 */
@Serializable
public data class ScanSession(
    val id: String,
    val createdAtMs: Long,
    val targetDocumentId: String? = null,
    val insertAfterPageId: String? = null,
    val captures: List<StagedCapture> = emptyList(),
    /** Folder a new document lands in (the folder the scan was started from). */
    val targetFolderId: String? = null,
) {
    val isReadyToCommit: Boolean get() = captures.isNotEmpty() && captures.all { it.status == StagedStatus.READY }
    val pendingCount: Int get() = captures.count { it.status == StagedStatus.CAPTURED || it.status == StagedStatus.PROCESSING }

    public fun capture(id: String): StagedCapture? = captures.firstOrNull { it.id == id }

    public fun updateCapture(id: String, transform: (StagedCapture) -> StagedCapture): ScanSession =
        copy(captures = captures.map { if (it.id == id) transform(it) else it })
}

/** NV-11/12: what a capture becomes when the scan is saved. */
@Serializable
public enum class CaptureKind {
    /** One page. */
    PAGE,
    /** ID card sides: saved together as one page with both sides at real card size. */
    ID_FRONT, ID_BACK,
    /** An open book: split into a left and a right page as soon as it has been straightened. */
    BOOK_SPREAD,
}

@Serializable
public enum class StagedStatus { CAPTURED, PROCESSING, READY, FAILED }

@Serializable
public data class StagedCapture(
    val id: String,
    val sourcePath: String,
    val previewPath: String,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** What the detector found on the still (or the live fallback). Null: nothing found. */
    val detectedQuad: Quad? = null,
    /** What the user accepted. */
    val quad: Quad = Quad.FULL,
    val rotation: Rotation = Rotation.R0,
    val mode: EnhancementMode = EnhancementMode.COLOUR,
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val basePath: String? = null,
    val processedPath: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** Bumped on every re-render so the UI never shows a stale cached image. */
    val revision: Int = 0,
    val status: StagedStatus = StagedStatus.CAPTURED,
    /** True until the capture has been normalised and detected. */
    val awaitingNormalize: Boolean = true,
    /** NV-10: sharpness of the last render's base image (see [com.avyaan.deckle.domain.imaging.SharpnessMeter]). */
    val sharpness: Float? = null,
    /** NV-10: the user chose to keep this page despite the blur warning. */
    val blurAccepted: Boolean = false,
    val kind: CaptureKind = CaptureKind.PAGE,
    /** NV-11: the front and back of one ID card share this id. */
    val group: String? = null,
) {
    /** NV-10: worth a "this page looks blurry" banner. */
    val looksBlurry: Boolean get() = !blurAccepted && com.avyaan.deckle.domain.imaging.BlurPolicy.isBlurry(sharpness)
}

public interface ScanSessionStore {
    public fun observe(sessionId: String): Flow<ScanSession?>
    public suspend fun get(sessionId: String): ScanSession?
    public suspend fun create(id: String, createdAtMs: Long, targetDocumentId: String?, insertAfterPageId: String?): ScanSession
    /** Serialised per session: the processing queue and the UI both mutate captures. Returns the new value. */
    public suspend fun update(sessionId: String, transform: (ScanSession) -> ScanSession): ScanSession?
    public suspend fun discard(sessionId: String)
    public suspend fun listAll(): List<ScanSession>
}

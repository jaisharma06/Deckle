package com.avyaan.deckle.feature.crop

import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.geometry.Corner
import com.avyaan.deckle.domain.geometry.CropConstraint
import com.avyaan.deckle.domain.geometry.CropHandle
import com.avyaan.deckle.domain.geometry.NudgeDirection
import com.avyaan.deckle.domain.geometry.PointN
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.Rotation
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.page.RecropPageUseCase
import com.avyaan.deckle.domain.scan.EditCaptureUseCase
import com.avyaan.deckle.domain.scan.RemoveCaptureUseCase
import com.avyaan.deckle.domain.scan.ScanSessionStore
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.ui.UiText
import com.avyaan.deckle.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.hypot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CropMode { NEW_CAPTURE, RECROP }

data class CropUiState(
    val mode: CropMode,
    /** preview.jpg (new capture) or the page's source (re-crop). Null until the still lane has written it. */
    val imagePath: String? = null,
    val quad: Quad = Quad.FULL,
    val detectedQuad: Quad? = null,
    val rotation: Rotation = Rotation.R0,
    val activeHandle: CropHandle? = null,
    /** Increments on each rejected move; the UI shakes and flashes the edges when it changes. */
    val rejections: Int = 0,
    /** True once detection has run (so "edges not found" can be shown honestly). */
    val detectionDone: Boolean = false,
    val saving: Boolean = false,
    val secure: Boolean = true,
    /** A gallery image rather than a photo just taken: "Retake" becomes "Cancel". */
    val imported: Boolean = false,
) {
    val isFullPage: Boolean get() = quad == Quad.FULL
    /** Full page ⇄ Auto only makes sense when there is something to go back to. */
    val canToggleAuto: Boolean get() = detectedQuad != null && detectedQuad != Quad.FULL
    val edgesNotFound: Boolean get() = detectionDone && detectedQuad == null
}

sealed interface CropEvent {
    data class DragStart(val handle: CropHandle) : CropEvent
    data class DragBy(val delta: PointN) : CropEvent
    data object DragEnd : CropEvent
    data class Nudge(val corner: Corner, val direction: NudgeDirection) : CropEvent
    data object RotateLeft : CropEvent
    data object RotateRight : CropEvent
    data object ToggleFullPage : CropEvent
    data object Retake : CropEvent
    data object Confirm : CropEvent
}

sealed interface CropEffect {
    data object GrabFeedback : CropEffect
    data object SnapFeedback : CropEffect
    data object RejectFeedback : CropEffect
    data object Retaken : CropEffect
    data class Done(val sessionId: String, val captureId: String, val fromReview: Boolean) : CropEffect
    data class Message(val text: UiText) : CropEffect
}

/**
 * Crop editor (spec 06 §4), for a fresh capture or a committed page (FR-42 re-crop).
 * The quad always refers to the unrotated image; rotation is tracked separately (FR-24).
 */
@HiltViewModel
class CropViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val sessions: ScanSessionStore,
    private val pages: PageRepository,
    private val detector: PageDetector,
    private val editCapture: EditCaptureUseCase,
    private val removeCapture: RemoveCaptureUseCase,
    private val recrop: RecropPageUseCase,
    private val discardSession: DiscardScanSessionUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    // Route arguments: CropDest(sessionId, captureId, fromReview) or RecropDest(pageId).
    private val pageId: String? = savedState["pageId"]
    private val sessionId: String? = savedState["sessionId"]
    private val captureId: String? = savedState["captureId"]
    private val fromReview: Boolean = savedState["fromReview"] ?: false
    private val discardEmptySession: Boolean = savedState["discardEmptySession"] ?: false

    private val _state = MutableStateFlow(
        CropUiState(
            mode = if (pageId != null) CropMode.RECROP else CropMode.NEW_CAPTURE,
            quad = savedState.get<FloatArray>(KEY_QUAD)?.toQuad() ?: Quad.FULL,
            rotation = savedState.get<Int>(KEY_ROTATION)?.let(Rotation::fromDegrees) ?: Rotation.R0,
            imported = savedState["imported"] ?: false,
        ),
    )
    val state: StateFlow<CropUiState> = _state

    private val effectChannel = Channel<CropEffect>(Channel.BUFFERED)
    val effects: Flow<CropEffect> = effectChannel.receiveAsFlow()

    /** True once the user (or a restore) owns the quad, so late detection results don't overwrite it. */
    private var userEdited: Boolean = savedState.contains(KEY_QUAD)
    private var dragTarget: PointN? = null
    private var snapped = false
    private var rejectStreak = false

    init {
        viewModelScope.launch { settings.settings.collect { s -> _state.update { it.copy(secure = s.secureScreens) } } }
        if (pageId != null) loadPage(pageId) else observeCapture()
    }

    private fun observeCapture() {
        val session = sessionId ?: return
        val capture = captureId ?: return
        viewModelScope.launch {
            sessions.observe(session).collect { s ->
                val c = s?.capture(capture) ?: return@collect
                _state.update {
                    it.copy(
                        imagePath = if (c.awaitingNormalize) null else c.previewPath,
                        detectedQuad = c.detectedQuad,
                        detectionDone = !c.awaitingNormalize,
                        quad = if (userEdited) it.quad else c.quad,
                        rotation = if (savedState.contains(KEY_ROTATION)) it.rotation else c.rotation,
                    )
                }
            }
        }
    }

    private fun loadPage(id: String) {
        viewModelScope.launch {
            val page = pages.get(id)
            val source = page?.sourcePath
            if (page == null || source == null) {
                send(CropEffect.Message(com.avyaan.deckle.domain.common.DeckleError.OriginalNotSaved.toUiText()))
                return@launch
            }
            _state.update {
                it.copy(
                    imagePath = source,
                    quad = if (userEdited) it.quad else page.corners,
                    rotation = if (savedState.contains(KEY_ROTATION)) it.rotation else page.rotation,
                )
            }
            val detected = detector.detect(source)
            _state.update { it.copy(detectedQuad = detected, detectionDone = true) }
        }
    }

    fun onEvent(event: CropEvent) {
        when (event) {
            is CropEvent.DragStart -> {
                val corner = (event.handle as? CropHandle.CornerHandle)?.corner
                dragTarget = corner?.let { _state.value.quad[it] }
                snapped = false
                rejectStreak = false
                _state.update { it.copy(activeHandle = event.handle) }
                send(CropEffect.GrabFeedback)
            }
            is CropEvent.DragBy -> drag(event.delta)
            CropEvent.DragEnd -> {
                dragTarget = null
                _state.update { it.copy(activeHandle = null) }
            }
            is CropEvent.Nudge -> applyMove(CropConstraint.nudge(_state.value.quad, event.corner, event.direction))
            CropEvent.RotateLeft -> setRotation(_state.value.rotation.counterClockwise())
            CropEvent.RotateRight -> setRotation(_state.value.rotation.clockwise())
            CropEvent.ToggleFullPage -> {
                val s = _state.value
                val target = if (s.isFullPage) s.detectedQuad ?: return else Quad.FULL
                setQuad(target)
            }
            CropEvent.Retake -> retake()
            CropEvent.Confirm -> confirm()
        }
    }

    /**
     * Corners follow an unsnapped "finger" point so a snap never fights the drag; within reach of the
     * detected corner the handle clicks into place (spec 05 §4.4).
     */
    private fun drag(delta: PointN) {
        val s = _state.value
        val handle = s.activeHandle ?: return
        if (handle is CropHandle.CornerHandle) {
            val finger = (dragTarget ?: s.quad[handle.corner]) + delta
            dragTarget = finger
            val detectedCorner = s.detectedQuad?.get(handle.corner)
            val snap = detectedCorner != null && hypot(finger.x - detectedCorner.x, finger.y - detectedCorner.y) < SNAP_DISTANCE
            val target = if (snap) detectedCorner!! else finger
            if (snap && !snapped) send(CropEffect.SnapFeedback)
            snapped = snap
            applyMove(CropConstraint.drag(s.quad, handle, target - s.quad[handle.corner]))
        } else {
            applyMove(CropConstraint.drag(s.quad, handle, delta))
        }
    }

    private fun applyMove(move: com.avyaan.deckle.domain.geometry.CropMove) {
        if (move.rejected) {
            // One reject haptic per streak, not per frame.
            if (!rejectStreak) send(CropEffect.RejectFeedback)
            rejectStreak = true
            _state.update { it.copy(rejections = it.rejections + 1) }
        } else {
            rejectStreak = false
            setQuad(move.quad)
        }
    }

    private fun setQuad(quad: Quad) {
        userEdited = true
        savedState[KEY_QUAD] = quad.toFloatArray()
        _state.update { it.copy(quad = quad) }
    }

    private fun setRotation(rotation: Rotation) {
        savedState[KEY_ROTATION] = rotation.degrees
        _state.update { it.copy(rotation = rotation) }
    }

    private fun retake() {
        val session = sessionId
        val capture = captureId
        viewModelScope.launch {
            if (session != null && capture != null) {
                removeCapture(session, capture)
                if (discardEmptySession && sessions.get(session)?.captures.isNullOrEmpty()) discardSession(session)
            }
            send(CropEffect.Retaken)
        }
    }

    private fun confirm() {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (pageId != null) {
                when (val r = recrop(pageId, s.quad, s.rotation)) {
                    is Outcome.Success -> send(CropEffect.Done("", pageId, fromReview = true))
                    is Outcome.Failure -> {
                        _state.update { it.copy(saving = false) }
                        send(CropEffect.Message(r.error.toUiText()))
                    }
                }
            } else {
                val session = sessionId ?: return@launch
                val capture = captureId ?: return@launch
                editCapture.setCrop(session, capture, s.quad, s.rotation)
                send(CropEffect.Done(session, capture, fromReview))
            }
        }
    }

    private fun send(effect: CropEffect) {
        effectChannel.trySend(effect)
    }

    private companion object {
        const val KEY_QUAD = "crop_quad"
        const val KEY_ROTATION = "crop_rotation"
        /** ~12 dp on a typical phone-sized image. */
        const val SNAP_DISTANCE = 0.025f

        fun Quad.toFloatArray() = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        fun FloatArray.toQuad() = Quad(PointN(this[0], this[1]), PointN(this[2], this[3]), PointN(this[4], this[5]), PointN(this[6], this[7]))
    }
}

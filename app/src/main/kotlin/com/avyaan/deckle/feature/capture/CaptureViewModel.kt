package com.avyaan.deckle.feature.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.avyaan.deckle.domain.common.MonotonicClock
import com.avyaan.deckle.domain.geometry.AutoCaptureInput
import com.avyaan.deckle.domain.geometry.AutoCaptureMachine
import com.avyaan.deckle.domain.geometry.AutoCaptureState
import com.avyaan.deckle.domain.geometry.Quad
import com.avyaan.deckle.domain.geometry.StabilityGate
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.scan.AddCaptureUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.ScanSessionStore
import com.avyaan.deckle.domain.scan.StartScanSessionUseCase
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.feature.capture.camera.DetectionResult
import com.avyaan.deckle.navigation.CaptureDest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Hint chip text (spec 05 §4.3). */
enum class CaptureHint { LOOKING, TIP, HOLD, CAPTURING, READY, NO_DETECTOR, BATCH }

data class CaptureUiState(
    val sessionId: String? = null,
    val detection: AutoCaptureState = AutoCaptureState.Searching,
    /** 0..1 progress of the auto-capture hold; drives the shutter arc. */
    val holdProgress: Float = 0f,
    /** Upright analysis frame size, for mapping the overlay onto the preview. 0 until the first frame. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val detectorAvailable: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val captureCount: Int = 0,
    /** Newest last; up to three, for the thumbnail stack. */
    val recentThumbs: ImmutableList<String> = persistentListOf(),
    val hint: CaptureHint? = null,
    val isCapturing: Boolean = false,
    val showTips: Boolean = false,
)

sealed interface CaptureEvent {
    /** Safe to send from any thread. */
    data class Detection(val result: DetectionResult) : CaptureEvent
    data object ShutterPressed : CaptureEvent
    /** The camera layer wrote the still to [path]. */
    data class PictureTaken(val path: String) : CaptureEvent
    data object PictureFailed : CaptureEvent
    data object CycleFlash : CaptureEvent
    data object ToggleAuto : CaptureEvent
    data class SetBatch(val on: Boolean) : CaptureEvent
    data class Imported(val uris: List<String>) : CaptureEvent
    data object OpenReview : CaptureEvent
    data object Close : CaptureEvent
    data object DiscardConfirmed : CaptureEvent
    data object DismissTips : CaptureEvent
}

sealed interface CaptureEffect {
    /** Manual or auto: the camera layer takes the picture and reports [CaptureEvent.PictureTaken]. */
    data object TakePicture : CaptureEffect
    data class OpenCrop(val sessionId: String, val captureId: String, val imported: Boolean = false) : CaptureEffect
    data class OpenReview(val sessionId: String) : CaptureEffect
    data class ConfirmDiscard(val count: Int) : CaptureEffect
    data object Exit : CaptureEffect
}

/**
 * Capture screen logic (spec 06 §2). Never touches CameraX: the camera layer feeds detections and stills
 * in, and executes [CaptureEffect.TakePicture]. That keeps this class JVM-testable with a fake clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    /** Exposed for the analyzer, which runs on the camera's executor. */
    val detector: PageDetector,
    private val clock: MonotonicClock,
    private val settingsRepo: SettingsRepository,
    private val startSession: StartScanSessionUseCase,
    private val addCapture: AddCaptureUseCase,
    private val discardSession: DiscardScanSessionUseCase,
    private val sessions: ScanSessionStore,
) : ViewModel() {

    private val route = savedState.toRoute<CaptureDest>()
    /** Coming back from Review ("Add page") means the pages belong to an existing flow: never discard them. */
    private val continuingSession = route.sessionId != null

    private val _state = MutableStateFlow(CaptureUiState(detectorAvailable = detector.isAvailable))
    val state: StateFlow<CaptureUiState> = _state

    private val effectChannel = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects: Flow<CaptureEffect> = effectChannel.receiveAsFlow()

    private val gate = StabilityGate()
    private var searchingSinceMs = clock.millis()
    private var batchHintUntilMs = 0L
    private var quadAtShutter: Quad? = null
    private var capturingSinceMs = 0L
    private val sessionId = MutableStateFlow<String?>(null)

    /** Detections arrive on the analysis thread; a conflated channel hands only the newest to the main thread. */
    private val detections = Channel<DetectionResult>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (result in detections) onDetection(result)
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(settings = s, showTips = !s.hasSeenCaptureTips) }
            }
        }
        viewModelScope.launch {
            val existing = savedState.get<String>(KEY_SESSION) ?: route.sessionId
            val session = startSession(existing, route.targetDocumentId, route.insertAfterPageId, targetFolderId = route.folderId)
            savedState[KEY_SESSION] = session.id
            sessionId.value = session.id
            _state.update { it.copy(sessionId = session.id) }
        }
        viewModelScope.launch {
            sessionId.filterNotNull().flatMapLatest { sessions.observe(it) }.collect { session ->
                val captures = session?.captures.orEmpty()
                _state.update {
                    it.copy(
                        captureCount = captures.size,
                        recentThumbs = captures.filterNot { c -> c.awaitingNormalize }.takeLast(3).map { c -> c.previewPath }.toImmutableList(),
                    )
                }
            }
        }
    }

    fun onEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Detection -> detections.trySend(event.result)
            CaptureEvent.ShutterPressed -> fire()
            is CaptureEvent.PictureTaken -> onPictureTaken(event.path)
            CaptureEvent.PictureFailed -> _state.update { it.copy(isCapturing = false) }
            CaptureEvent.CycleFlash -> updateSettings { it.copy(flash = it.flash.next()) }
            CaptureEvent.ToggleAuto -> updateSettings { it.copy(autoCapture = !it.autoCapture) }
            is CaptureEvent.SetBatch -> {
                if (event.on) batchHintUntilMs = clock.millis() + BATCH_HINT_MS
                updateSettings { it.copy(batchMode = event.on) }
            }
            is CaptureEvent.Imported -> onImported(event.uris)
            CaptureEvent.OpenReview -> sessionId.value?.let { if (_state.value.captureCount > 0) send(CaptureEffect.OpenReview(it)) }
            CaptureEvent.Close -> onClose()
            CaptureEvent.DiscardConfirmed -> viewModelScope.launch {
                sessionId.value?.let { discardSession(it) }
                send(CaptureEffect.Exit)
            }
            CaptureEvent.DismissTips -> updateSettings { it.copy(hasSeenCaptureTips = true) }
        }
    }

    private fun onDetection(result: DetectionResult) {
        val now = clock.millis()
        val aspect = if (result.uprightHeight == 0) 0.75f else result.uprightWidth.toFloat() / result.uprightHeight
        val stable = gate.offer(result.quad, aspect)
        val current = _state.value
        val next = AutoCaptureMachine.next(
            current.detection,
            AutoCaptureInput.Detection(result.quad, stable, aspect),
            now,
            autoCaptureEnabled = current.settings.autoCapture && current.detectorAvailable,
        )
        if (next !is AutoCaptureState.Searching) searchingSinceMs = now
        _state.update {
            it.copy(
                detection = next,
                holdProgress = (next as? AutoCaptureState.Holding)?.progress(now) ?: 0f,
                frameWidth = result.uprightWidth,
                frameHeight = result.uprightHeight,
                hint = hintFor(it, next, now),
            )
        }
        if (next is AutoCaptureState.Firing) fire()
    }

    private fun hintFor(s: CaptureUiState, d: AutoCaptureState, now: Long): CaptureHint? {
        val auto = s.settings.autoCapture
        return when {
            !s.detectorAvailable -> CaptureHint.NO_DETECTOR
            s.settings.batchMode && s.captureCount == 0 && now < batchHintUntilMs -> CaptureHint.BATCH
            d is AutoCaptureState.Searching && now - searchingSinceMs > 5_000 -> CaptureHint.TIP
            d is AutoCaptureState.Searching && now - searchingSinceMs > 1_500 -> CaptureHint.LOOKING
            d is AutoCaptureState.Tracking && auto -> CaptureHint.HOLD
            d is AutoCaptureState.Holding || d is AutoCaptureState.Firing -> if (auto) CaptureHint.CAPTURING else CaptureHint.READY
            else -> null
        }
    }

    /** Shutter (manual) or auto-capture. Debounced so a double tap never takes two pictures. */
    private fun fire() {
        val now = clock.millis()
        val s = _state.value
        if (s.sessionId == null) return
        if (s.isCapturing && now - capturingSinceMs < MIN_SHUTTER_INTERVAL_MS) return
        capturingSinceMs = now
        quadAtShutter = s.detection.quad
        gate.reset()
        _state.update {
            it.copy(
                isCapturing = true,
                holdProgress = 0f,
                detection = AutoCaptureMachine.next(it.detection, AutoCaptureInput.CaptureStarted, now, it.settings.autoCapture),
            )
        }
        send(CaptureEffect.TakePicture)
    }

    private fun onPictureTaken(path: String) {
        val session = sessionId.value ?: return
        val liveQuad = quadAtShutter
        val batch = _state.value.settings.batchMode
        viewModelScope.launch {
            val captureId = addCapture(session, ImageInput.FilePath(path), liveQuad)
            // Never block the next shot for longer than the debounce window (FR-18).
            val wait = MIN_SHUTTER_INTERVAL_MS - (clock.millis() - capturingSinceMs)
            if (wait > 0) delay(wait)
            _state.update { it.copy(isCapturing = false) }
            if (!batch) send(CaptureEffect.OpenCrop(session, captureId))
        }
    }

    private fun onImported(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val session = sessionId.filterNotNull().first()
            val ids = uris.map { addCapture(session, ImageInput.ContentUri(it), liveQuad = null) }
            // One image goes straight to the crop editor, like a photo just taken; several go to Review,
            // where each page can be cropped.
            send(if (ids.size == 1) CaptureEffect.OpenCrop(session, ids.single(), imported = true) else CaptureEffect.OpenReview(session))
        }
    }

    private fun onClose() {
        val count = _state.value.captureCount
        val session = sessionId.value
        when {
            count == 0 -> viewModelScope.launch {
                if (!continuingSession && session != null) discardSession(session)
                send(CaptureEffect.Exit)
            }
            continuingSession && session != null -> send(CaptureEffect.OpenReview(session))
            else -> send(CaptureEffect.ConfirmDiscard(count))
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    private fun send(effect: CaptureEffect) {
        effectChannel.trySend(effect)
    }

    private companion object {
        const val KEY_SESSION = "sessionId"
        const val MIN_SHUTTER_INTERVAL_MS = 250L
        const val BATCH_HINT_MS = 3_000L
    }
}

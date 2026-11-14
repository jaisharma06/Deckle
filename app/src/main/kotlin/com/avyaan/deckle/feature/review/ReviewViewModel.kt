package com.avyaan.deckle.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.avyaan.deckle.domain.common.ApplicationScope
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.scan.CommitScanSessionUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.EditCaptureUseCase
import com.avyaan.deckle.domain.scan.RemoveCaptureUseCase
import com.avyaan.deckle.domain.scan.ResumeProcessingUseCase
import com.avyaan.deckle.domain.scan.ScanSession
import com.avyaan.deckle.domain.scan.ScanSessionStore
import com.avyaan.deckle.domain.scan.StagedCapture
import com.avyaan.deckle.domain.scan.StagedStatus
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.navigation.ReviewDest
import com.avyaan.deckle.ui.UiText
import com.avyaan.deckle.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewPageUi(
    val id: String,
    /** What to show now: the finished render, else the latest quick preview, else the raw preview. */
    val displayPath: String?,
    val status: StagedStatus,
    val mode: EnhancementMode,
    val adjustments: Adjustments,
    /** Quarter turns requested but not yet rendered; the image turns optimistically meanwhile. */
    val pendingTurns: Int,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val pages: ImmutableList<ReviewPageUi> = persistentListOf(),
    val currentIndex: Int = 0,
    /** Small live previews of each filter for the current page (FR-31). */
    val filterThumbs: ImmutableMap<EnhancementMode, String> = persistentMapOf(),
    val previewBusy: Boolean = false,
    val adjustOpen: Boolean = false,
    val pendingCount: Int = 0,
    val canFinish: Boolean = false,
    val committing: Boolean = false,
    val secure: Boolean = true,
    /** NV-01: picking several pages to delete, rotate or re-filter together. */
    val selecting: Boolean = false,
    val selection: ImmutableSet<String> = persistentSetOf(),
) {
    val current: ReviewPageUi? get() = pages.getOrNull(currentIndex)

    /** The mode shared by every selected page, else the current page's, so the strip shows something sensible. */
    val selectionMode: EnhancementMode?
        get() = pages.filter { it.id in selection }.map { it.mode }.distinct().singleOrNull()
}

sealed interface ReviewEvent {
    data class PageChanged(val index: Int) : ReviewEvent
    data class SelectFilter(val mode: EnhancementMode) : ReviewEvent
    /** While a slider moves: preview only. */
    data class AdjustPreview(val adjustments: Adjustments) : ReviewEvent
    /** Slider released or Reset: persist and render. */
    data class AdjustCommit(val adjustments: Adjustments) : ReviewEvent
    data class SetAdjustOpen(val open: Boolean) : ReviewEvent
    data object ApplyToAll : ReviewEvent
    data object Rotate : ReviewEvent
    data object Crop : ReviewEvent
    data object DeletePage : ReviewEvent
    data class UndoDelete(val captureIds: List<String>) : ReviewEvent
    data class ConfirmDelete(val captureIds: List<String>) : ReviewEvent
    data class SetSelecting(val on: Boolean) : ReviewEvent
    data class ToggleSelect(val captureId: String) : ReviewEvent
    data object SelectAll : ReviewEvent
    data object DeleteSelected : ReviewEvent
    data object RotateSelected : ReviewEvent
    data class FilterSelected(val mode: EnhancementMode) : ReviewEvent
    data object AddPage : ReviewEvent
    data object Done : ReviewEvent
}

sealed interface ReviewEffect {
    data class OpenCrop(val sessionId: String, val captureId: String) : ReviewEffect
    data class OpenCapture(val sessionId: String) : ReviewEffect
    /** One or more pages were hidden; the screen offers Undo, then confirms. */
    data class PagesDeleted(val captureIds: List<String>) : ReviewEffect
    data class Finished(val documentId: String) : ReviewEffect
    data object Discarded : ReviewEffect
    data class Message(val text: UiText) : ReviewEffect
}

/**
 * Review pager (spec 06 §5, D-9). Edits are written to the session and re-rendered by the queue, so
 * "Done" only has to move files. For immediate feedback, filter changes also render a quick preview
 * straight from the base image — never from a previous render (FR-30/31).
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val sessions: ScanSessionStore,
    private val edit: EditCaptureUseCase,
    private val removeCapture: RemoveCaptureUseCase,
    private val discard: DiscardScanSessionUseCase,
    private val commit: CommitScanSessionUseCase,
    private val enhancer: PageEnhancer,
    private val files: PageFileStore,
    resumeProcessing: ResumeProcessingUseCase,
    settings: SettingsRepository,
    /** Deletes must finish even if this screen is already gone. */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedState.toRoute<ReviewDest>()
    private val sessionId = route.sessionId

    private val ui = MutableStateFlow(
        UiLocal(
            index = savedState[KEY_INDEX] ?: -1,
            adjustOpen = savedState[KEY_ADJUST] ?: false,
        ),
    )
    private val effectChannel = Channel<ReviewEffect>(Channel.BUFFERED)
    val effects: Flow<ReviewEffect> = effectChannel.receiveAsFlow()

    private var thumbsJob: Job? = null
    private var previewJob: Job? = null
    private var previewSeq = 0
    private var latestSession: ScanSession? = null

    val state: StateFlow<ReviewUiState> = combine(sessions.observe(sessionId), ui, settings.settings) { session, local, s ->
        latestSession = session
        val visible = session?.captures.orEmpty().filterNot { it.id in local.hidden }
        val index = when {
            visible.isEmpty() -> 0
            local.index < 0 -> visible.indexOfFirst { it.id == route.captureId }.takeIf { it >= 0 } ?: visible.lastIndex
            else -> local.index.coerceIn(0, visible.lastIndex)
        }
        ReviewUiState(
            loading = session == null,
            pages = visible.map { it.toUi(local) }.toImmutableList(),
            currentIndex = index,
            filterThumbs = local.thumbs[visible.getOrNull(index)?.id].orEmpty().toImmutableMap(),
            previewBusy = local.previewBusy,
            adjustOpen = local.adjustOpen,
            pendingCount = visible.count { it.status == StagedStatus.CAPTURED || it.status == StagedStatus.PROCESSING },
            canFinish = visible.isNotEmpty() && visible.all { it.status == StagedStatus.READY },
            committing = local.committing,
            secure = s.secureScreens,
            selecting = local.selecting,
            selection = local.selection.filterTo(LinkedHashSet()) { id -> visible.any { it.id == id } }.toImmutableSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    init {
        // Re-queue anything unfinished — this is how a scan survives process death (spec 02 §5.2).
        viewModelScope.launch { resumeProcessing(sessionId) }
        // Filter thumbnails follow the current page's base image.
        viewModelScope.launch {
            state.collect { s ->
                val c = s.current ?: return@collect
                val capture = latestSession?.capture(c.id) ?: return@collect
                val base = capture.basePath ?: return@collect
                val key = "${c.id}|$base"
                if (ui.value.thumbsKey[c.id] != key) renderThumbs(c.id, base, key)
            }
        }
    }

    fun onEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.PageChanged -> {
                savedState[KEY_INDEX] = event.index
                ui.update { it.copy(index = event.index) }
            }
            is ReviewEvent.SelectFilter -> current()?.let { c -> setFilter(c, event.mode, c.adjustments) }
            is ReviewEvent.AdjustPreview -> current()?.let { c -> quickPreview(c, c.mode, event.adjustments, ADJUST_PREVIEW_EDGE, throttle = true) }
            is ReviewEvent.AdjustCommit -> current()?.let { c -> setFilter(c, c.mode, event.adjustments) }
            is ReviewEvent.SetAdjustOpen -> {
                savedState[KEY_ADJUST] = event.open
                ui.update { it.copy(adjustOpen = event.open) }
            }
            ReviewEvent.ApplyToAll -> current()?.let { c ->
                viewModelScope.launch { edit.setFilterForAll(sessionId, c.mode, c.adjustments) }
            }
            ReviewEvent.Rotate -> current()?.let { c ->
                turnOptimistically(listOf(c.id))
                viewModelScope.launch { edit.rotate(sessionId, c.id, clockwise = true) }
            }
            ReviewEvent.Crop -> current()?.let { send(ReviewEffect.OpenCrop(sessionId, it.id)) }
            ReviewEvent.DeletePage -> current()?.let { c -> hide(listOf(c.id)) }
            is ReviewEvent.UndoDelete -> ui.update { it.copy(hidden = it.hidden - event.captureIds.toSet()) }
            is ReviewEvent.ConfirmDelete -> appScope.launch {
                event.captureIds.forEach { removeCapture(sessionId, it) }
                ui.update { it.copy(hidden = it.hidden - event.captureIds.toSet()) }
                if (sessions.get(sessionId)?.captures.isNullOrEmpty()) {
                    discard(sessionId)
                    send(ReviewEffect.Discarded)
                }
            }
            is ReviewEvent.SetSelecting -> ui.update { it.copy(selecting = event.on, selection = emptySet()) }
            is ReviewEvent.ToggleSelect -> ui.update {
                val sel = if (event.captureId in it.selection) it.selection - event.captureId else it.selection + event.captureId
                it.copy(selecting = true, selection = sel)
            }
            ReviewEvent.SelectAll -> ui.update { it.copy(selection = state.value.pages.map { p -> p.id }.toSet()) }
            ReviewEvent.DeleteSelected -> {
                val ids = selectedInOrder()
                ui.update { it.copy(selecting = false, selection = emptySet()) }
                if (ids.isNotEmpty()) hide(ids)
            }
            ReviewEvent.RotateSelected -> selectedInOrder().takeIf { it.isNotEmpty() }?.let { ids ->
                turnOptimistically(ids)
                viewModelScope.launch { edit.rotateAll(sessionId, ids.toSet(), clockwise = true) }
            }
            is ReviewEvent.FilterSelected -> selectedInOrder().takeIf { it.isNotEmpty() }?.let { ids ->
                // Drop quick previews of those pages so the old filter doesn't linger until their render lands.
                ui.update { it.copy(previews = it.previews - ids.toSet()) }
                viewModelScope.launch { edit.setModeFor(sessionId, ids.toSet(), event.mode) }
            }
            ReviewEvent.AddPage -> send(ReviewEffect.OpenCapture(sessionId))
            ReviewEvent.Done -> finish()
        }
    }

    /**
     * The Undo snackbar belongs to the screen; leaving it (Back, Add page) cancels the snackbar before it can
     * confirm. Pages still hidden at that point were deleted by the user, so remove them for real.
     */
    override fun onCleared() {
        val pending = ui.value.hidden
        if (pending.isNotEmpty()) appScope.launch { pending.forEach { removeCapture(sessionId, it) } }
    }

    /** Hidden at once; really removed only when the Undo snackbar goes away (spec 05 §5). */
    private fun hide(ids: List<String>) {
        ui.update { it.copy(hidden = it.hidden + ids) }
        send(ReviewEffect.PagesDeleted(ids))
    }

    private fun selectedInOrder(): List<String> {
        val sel = ui.value.selection
        return state.value.pages.map { it.id }.filter { it in sel }
    }

    /** The image turns at once; the turn settles when a render newer than this request is READY. */
    private fun turnOptimistically(ids: List<String>) {
        val pending = state.value.pages.associate { it.id to it.pendingTurns }
        val revisions = ids.mapNotNull { id -> latestSession?.capture(id)?.let { id to it.revision } }
        ui.update { l ->
            l.copy(
                turns = l.turns + ids.map { it to (pending[it] ?: 0) + 1 },
                turnsAtRevision = l.turnsAtRevision + revisions,
            )
        }
    }

    private fun current(): StagedCapture? {
        val s = state.value
        val id = s.current?.id ?: return null
        return latestSession?.capture(id)
    }

    private fun setFilter(c: StagedCapture, mode: EnhancementMode, adjustments: Adjustments) {
        quickPreview(c, mode, adjustments, ImageNormalizer.PREVIEW_LONG_EDGE, throttle = false)
        viewModelScope.launch { edit.setFilter(sessionId, c.id, mode, adjustments) }
    }

    /**
     * Renders from the base image. The newest request wins; the thin progress line appears only if the
     * render takes longer than 120 ms (spec 05 §4.5). Slider drags are throttled to ~8 Hz.
     */
    private fun quickPreview(c: StagedCapture, mode: EnhancementMode, adjustments: Adjustments, longEdge: Int, throttle: Boolean) {
        val base = c.basePath ?: return
        if (throttle && previewJob?.isActive == true) return
        previewJob?.cancel()
        val seq = ++previewSeq
        previewJob = viewModelScope.launch {
            val busy = launch { delay(PROGRESS_DELAY_MS); ui.update { it.copy(previewBusy = true) } }
            val out = files.cachePath("preview/${c.id}-$seq.jpg")
            val result = enhancer.preview(base, mode, adjustments, longEdge, out)
            busy.cancel()
            if (result is Outcome.Success && seq == previewSeq) {
                ui.update { it.copy(previews = it.previews + (c.id to result.value), previewBusy = false) }
            } else {
                ui.update { it.copy(previewBusy = false) }
            }
            if (throttle) delay(THROTTLE_MS)
        }
    }

    private fun renderThumbs(captureId: String, base: String, key: String) {
        ui.update { it.copy(thumbsKey = it.thumbsKey + (captureId to key)) }
        thumbsJob?.cancel()
        thumbsJob = viewModelScope.launch {
            val tag = key.hashCode().toUInt().toString(16)
            EnhancementMode.entries.forEach { mode ->
                val out = files.cachePath("filters/$captureId-$tag-${mode.name.lowercase()}.jpg")
                val r = enhancer.preview(base, mode, Adjustments.NEUTRAL, FILTER_THUMB_EDGE, out)
                if (r is Outcome.Success) {
                    ui.update { it.copy(thumbs = it.thumbs + (captureId to (it.thumbs[captureId].orEmpty() + (mode to r.value)))) }
                }
            }
        }
    }

    private fun finish() {
        if (!state.value.canFinish || ui.value.committing) return
        ui.update { it.copy(committing = true) }
        viewModelScope.launch {
            // Pages deleted with a pending Undo must not be committed.
            ui.value.hidden.forEach { removeCapture(sessionId, it) }
            when (val r = commit(sessionId)) {
                is Outcome.Success -> send(ReviewEffect.Finished(r.value))
                is Outcome.Failure -> {
                    ui.update { it.copy(committing = false) }
                    send(ReviewEffect.Message(r.error.toUiText()))
                }
            }
        }
    }

    private fun StagedCapture.toUi(local: UiLocal): ReviewPageUi {
        // A finished render supersedes the quick preview and any optimistic turn.
        val rendered = status == StagedStatus.READY
        // Settled only once a render newer than the rotate request is READY (an older in-flight render
        // finishing first must not snap the image back).
        val requestedAt = local.turnsAtRevision[id]
        val settled = requestedAt == null || (rendered && revision > requestedAt)
        val turnsPending = if (settled) 0 else local.turns[id] ?: 0
        return ReviewPageUi(
            id = id,
            displayPath = if (rendered) processedPath else local.previews[id] ?: processedPath ?: previewPath.takeUnless { awaitingNormalize },
            status = status,
            mode = mode,
            adjustments = adjustments,
            pendingTurns = turnsPending,
        )
    }

    private fun send(effect: ReviewEffect) {
        effectChannel.trySend(effect)
    }

    /** Screen-local state that isn't in the session file. */
    private data class UiLocal(
        val index: Int,
        val adjustOpen: Boolean,
        val hidden: Set<String> = emptySet(),
        val previews: Map<String, String> = emptyMap(),
        val thumbs: Map<String, Map<EnhancementMode, String>> = emptyMap(),
        val thumbsKey: Map<String, String> = emptyMap(),
        val turns: Map<String, Int> = emptyMap(),
        val turnsAtRevision: Map<String, Int> = emptyMap(),
        val previewBusy: Boolean = false,
        val committing: Boolean = false,
        val selecting: Boolean = false,
        val selection: Set<String> = emptySet(),
    )

    private companion object {
        const val KEY_INDEX = "review_index"
        const val KEY_ADJUST = "review_adjust"
        const val FILTER_THUMB_EDGE = 240
        const val ADJUST_PREVIEW_EDGE = 1024
        const val PROGRESS_DELAY_MS = 120L
        const val THROTTLE_MS = 125L
    }
}

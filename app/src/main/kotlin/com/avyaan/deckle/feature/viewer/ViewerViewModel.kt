package com.avyaan.deckle.feature.viewer

import com.avyaan.deckle.domain.page.RenamePageUseCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.avyaan.deckle.domain.common.ApplicationScope
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.imaging.Adjustments
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.page.DeletePageUseCase
import com.avyaan.deckle.domain.page.ObservePagesUseCase
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageFileStore
import com.avyaan.deckle.domain.page.ReEnhancePageUseCase
import com.avyaan.deckle.domain.page.RotatePageUseCase
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.navigation.ViewerDest
import com.avyaan.deckle.ui.UiText
import com.avyaan.deckle.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ViewerPageUi(
    val id: String,
    val imagePath: String,
    val hasSource: Boolean,
    val mode: EnhancementMode,
    val adjustments: Adjustments,
    val name: String? = null,
)

data class ViewerUiState(
    val loading: Boolean = true,
    val pages: ImmutableList<ViewerPageUi> = persistentListOf(),
    val currentIndex: Int = 0,
    val chromeVisible: Boolean = true,
    /** The page currently being re-rendered (rotate or filter). */
    val busyPageId: String? = null,
    val filterOpen: Boolean = false,
    val filterThumbs: ImmutableMap<EnhancementMode, String> = persistentMapOf(),
    val secure: Boolean = true,
) {
    val current: ViewerPageUi? get() = pages.getOrNull(currentIndex)
}

sealed interface ViewerEvent {
    data class PageChanged(val index: Int) : ViewerEvent
    data object ToggleChrome : ViewerEvent
    data object Recrop : ViewerEvent
    data class SetFilterOpen(val open: Boolean) : ViewerEvent
    data class ApplyFilter(val mode: EnhancementMode, val adjustments: Adjustments) : ViewerEvent
    data object Rotate : ViewerEvent
    data object InsertAfter : ViewerEvent
    data class Rename(val name: String) : ViewerEvent
    data object Delete : ViewerEvent
    data class UndoDelete(val pageId: String) : ViewerEvent
    data class ConfirmDelete(val pageId: String) : ViewerEvent
}

sealed interface ViewerEffect {
    data class OpenRecrop(val pageId: String) : ViewerEffect
    data class OpenInsert(val documentId: String, val afterPageId: String) : ViewerEffect
    data class PageDeleted(val pageId: String) : ViewerEffect
    data class Message(val text: UiText) : ViewerEffect
    data object Exit : ViewerEffect
}

/** Full-screen page viewer with per-page tools (spec 06 §7, FR-41/42). */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    observePages: ObservePagesUseCase,
    settings: SettingsRepository,
    private val reEnhance: ReEnhancePageUseCase,
    private val rotate: RotatePageUseCase,
    private val deletePage: DeletePageUseCase,
    private val enhancer: PageEnhancer,
    private val files: PageFileStore,
    private val renamePage: RenamePageUseCase,
    /** Deletes must finish even if this screen is already gone. */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedState.toRoute<ViewerDest>()
    val documentId: String get() = route.documentId
    private val local = MutableStateFlow(Local(currentPageId = savedState[KEY_PAGE] ?: route.pageId))
    private val effectChannel = Channel<ViewerEffect>(Channel.BUFFERED)
    val effects: Flow<ViewerEffect> = effectChannel.receiveAsFlow()
    private var latestPages: List<Page> = emptyList()

    val state: StateFlow<ViewerUiState> = combine(observePages(route.documentId), local, settings.settings) { pages, l, s ->
        val visible = pages.sortedBy { it.orderIndex }.filterNot { it.id in l.hidden }
        latestPages = visible
        val index = visible.indexOfFirst { it.id == l.currentPageId }.takeIf { it >= 0 } ?: l.lastIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
        ViewerUiState(
            loading = false,
            pages = visible.map { ViewerPageUi(it.id, it.processedPath, it.sourcePath != null, it.mode, it.adjustments, it.name) }.toImmutableList(),
            currentIndex = index,
            chromeVisible = l.chrome,
            busyPageId = l.busy,
            filterOpen = l.filterOpen,
            filterThumbs = l.thumbs.toImmutableMap(),
            secure = s.secureScreens,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    fun onEvent(event: ViewerEvent) {
        when (event) {
            is ViewerEvent.PageChanged -> state.value.pages.getOrNull(event.index)?.let { page ->
                savedState[KEY_PAGE] = page.id
                local.update { it.copy(currentPageId = page.id, lastIndex = event.index) }
            }
            ViewerEvent.ToggleChrome -> local.update { it.copy(chrome = !it.chrome) }
            ViewerEvent.Recrop -> current()?.let { p ->
                if (p.sourcePath == null) send(ViewerEffect.Message(DeckleError.OriginalNotSaved.toUiText()))
                else send(ViewerEffect.OpenRecrop(p.id))
            }
            is ViewerEvent.SetFilterOpen -> {
                local.update { it.copy(filterOpen = event.open, thumbs = if (event.open) it.thumbs else emptyMap()) }
                if (event.open) current()?.let(::renderThumbs)
            }
            is ViewerEvent.ApplyFilter -> current()?.let { p ->
                runBusy(p.id) { reEnhance(p.id, event.mode, event.adjustments) }
            }
            ViewerEvent.Rotate -> current()?.let { p -> runBusy(p.id) { rotate(p.id, clockwise = true) } }
            ViewerEvent.InsertAfter -> current()?.let { send(ViewerEffect.OpenInsert(route.documentId, it.id)) }
            is ViewerEvent.Rename -> current()?.let { p -> viewModelScope.launch { renamePage(p.id, event.name) } }
            ViewerEvent.Delete -> current()?.let { p ->
                local.update { it.copy(hidden = it.hidden + p.id) }
                send(ViewerEffect.PageDeleted(p.id))
            }
            is ViewerEvent.UndoDelete -> local.update { it.copy(hidden = it.hidden - event.pageId, currentPageId = event.pageId) }
            is ViewerEvent.ConfirmDelete -> appScope.launch {
                deletePage(event.pageId)
                local.update { it.copy(hidden = it.hidden - event.pageId) }
                if (latestPages.isEmpty()) send(ViewerEffect.Exit)
            }
        }
    }

    /**
     * The Undo snackbar belongs to the screen; leaving it (Back, Crop, Insert) cancels the snackbar before it
     * can confirm. Pages still hidden at that point were deleted by the user, so delete them for real.
     */
    override fun onCleared() {
        val pending = local.value.hidden
        if (pending.isNotEmpty()) appScope.launch { pending.forEach { deletePage(it) } }
    }

    private fun current(): Page? = state.value.current?.id?.let { id -> latestPages.firstOrNull { it.id == id } }

    private fun runBusy(pageId: String, block: suspend () -> Outcome<Unit>) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = pageId) }
        viewModelScope.launch {
            val result = block()
            local.update { it.copy(busy = null) }
            if (result is Outcome.Failure) send(ViewerEffect.Message(result.error.toUiText()))
        }
    }

    private fun renderThumbs(page: Page) {
        viewModelScope.launch {
            EnhancementMode.entries.forEach { mode ->
                val out = files.cachePath("filters/${page.id}-${page.basePath.hashCode().toUInt().toString(16)}-${mode.name.lowercase()}.jpg")
                val r = enhancer.preview(page.basePath, mode, Adjustments.NEUTRAL, THUMB_EDGE, out)
                if (r is Outcome.Success) local.update { it.copy(thumbs = it.thumbs + (mode to r.value)) }
            }
        }
    }

    private fun send(effect: ViewerEffect) {
        effectChannel.trySend(effect)
    }

    private data class Local(
        val currentPageId: String,
        val lastIndex: Int = 0,
        val chrome: Boolean = true,
        val busy: String? = null,
        val filterOpen: Boolean = false,
        val thumbs: Map<EnhancementMode, String> = emptyMap(),
        val hidden: Set<String> = emptySet(),
    )

    private companion object {
        const val KEY_PAGE = "viewer_page"
        const val THUMB_EDGE = 240
    }
}

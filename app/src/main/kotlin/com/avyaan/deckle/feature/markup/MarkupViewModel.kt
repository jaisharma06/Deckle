package com.avyaan.deckle.feature.markup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.markup.MarkupGeometry
import com.avyaan.deckle.domain.markup.MarkupIds
import com.avyaan.deckle.domain.markup.MarkupItem
import com.avyaan.deckle.domain.markup.PageMarkup
import com.avyaan.deckle.domain.markup.SaveMarkupUseCase
import com.avyaan.deckle.domain.markup.SaveSignatureUseCase
import com.avyaan.deckle.domain.markup.SavedSignature
import com.avyaan.deckle.domain.markup.SignatureInk
import com.avyaan.deckle.domain.markup.SignatureStore
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.navigation.MarkupDest
import com.avyaan.deckle.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** NV-41/42/44 tools. Signing (NV-40) is an action that places a signature, then switches to [SELECT]. */
enum class MarkupTool { PEN, HIGHLIGHTER, TEXT, REDACT, SELECT }

data class MarkupUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val imagePath: String = "",
    val widthPx: Int = 1,
    val heightPx: Int = 1,
    val markup: PageMarkup = PageMarkup.EMPTY,
    val tool: MarkupTool = MarkupTool.PEN,
    val penColor: Long = MarkupItem.INK_BLUE,
    val highlightColor: Long = MarkupItem.HIGHLIGHT_YELLOW,
    val selectedId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Changed since opening; closing asks before throwing marks away. */
    val dirty: Boolean = false,
    val signatures: ImmutableList<SavedSignature> = persistentListOf(),
    val secure: Boolean = true,
) {
    /** Page height / width. */
    val aspect: Float get() = heightPx.toFloat() / widthPx.coerceAtLeast(1)
    val selected: MarkupItem? get() = markup.items.firstOrNull { it.id == selectedId }
}

sealed interface MarkupEvent {
    data class SetTool(val tool: MarkupTool) : MarkupEvent
    data class SetColor(val color: Long) : MarkupEvent
    /** A finished stroke, box or text. */
    data class Add(val item: MarkupItem) : MarkupEvent
    data class Select(val id: String?) : MarkupEvent
    /** End of a drag/pinch on an item: move by (dx, dy) normalised, scale and turn. */
    data class Transform(val id: String, val dx: Float, val dy: Float, val zoom: Float, val rotation: Float) : MarkupEvent
    data class EditText(val id: String, val text: String) : MarkupEvent
    data object DeleteSelected : MarkupEvent
    data object Undo : MarkupEvent
    data object Redo : MarkupEvent
    data class PlaceSignature(val ink: SignatureInk) : MarkupEvent
    /** Raw strokes from the signature pad, in its own pixels. Saved (encrypted), then placed. */
    data class SaveSignature(val strokes: List<List<Float>>) : MarkupEvent
    data class DeleteSignature(val id: String) : MarkupEvent
    data object Save : MarkupEvent
}

sealed interface MarkupEffect {
    data object Close : MarkupEffect
    data class Message(val text: UiText) : MarkupEffect
}

/**
 * The page editor (NV-40…44). Marks live in memory with an undo history until Done; the stored page image is
 * never touched. Every committed change is mirrored to [SavedStateHandle], so a process death mid-edit keeps
 * the work.
 */
@HiltViewModel
class MarkupViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val pages: PageRepository,
    private val saveMarkup: SaveMarkupUseCase,
    private val saveSignature: SaveSignatureUseCase,
    private val signatures: SignatureStore,
    private val ids: MarkupIds,
    settings: SettingsRepository,
) : ViewModel() {

    private val pageId: String = savedState.toRoute<MarkupDest>().pageId
    private val local = MutableStateFlow(MarkupUiState())
    private val undo = ArrayDeque<PageMarkup>()
    private val redo = ArrayDeque<PageMarkup>()

    private val effectChannel = Channel<MarkupEffect>(Channel.BUFFERED)
    val effects: Flow<MarkupEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<MarkupUiState> = combine(local.asStateFlow(), settings.settings) { l, s -> l.copy(secure = s.secureScreens) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarkupUiState())

    init {
        viewModelScope.launch {
            val page = pages.get(pageId)
            if (page == null) {
                local.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            // An edit in progress before process death wins, including "everything erased" ({}).
            val restored = savedState.get<String>(KEY_MARKUP)?.let { PageMarkup.decode(it) ?: PageMarkup.EMPTY }
            val markup = restored ?: page.markup ?: PageMarkup.EMPTY
            local.update {
                it.copy(
                    loading = false,
                    imagePath = page.processedPath,
                    widthPx = page.widthPx,
                    heightPx = page.heightPx,
                    markup = markup,
                    dirty = restored != null && restored != (page.markup ?: PageMarkup.EMPTY),
                )
            }
            refreshSignatures()
        }
    }

    fun onEvent(event: MarkupEvent) {
        when (event) {
            is MarkupEvent.SetTool -> local.update { it.copy(tool = event.tool, selectedId = if (event.tool == MarkupTool.SELECT) it.selectedId else null) }
            is MarkupEvent.SetColor -> local.update {
                if (it.tool == MarkupTool.HIGHLIGHTER) it.copy(highlightColor = event.color) else it.copy(penColor = event.color)
            }
            is MarkupEvent.Add -> commit { it + event.item }
            is MarkupEvent.Select -> local.update { it.copy(selectedId = event.id) }
            is MarkupEvent.Transform -> {
                if (event.dx == 0f && event.dy == 0f && event.zoom == 1f && event.rotation == 0f) return
                commit { items ->
                    items.map { item ->
                        if (item.id != event.id) item
                        else MarkupGeometry.transform(MarkupGeometry.translate(item, event.dx, event.dy), event.zoom, event.rotation)
                    }
                }
            }
            is MarkupEvent.EditText -> commit { items ->
                if (event.text.isBlank()) items.filterNot { it.id == event.id }
                else items.map { if (it.id == event.id && it is MarkupItem.Text) it.copy(text = event.text) else it }
            }
            MarkupEvent.DeleteSelected -> {
                val id = local.value.selectedId ?: return
                commit { items -> items.filterNot { it.id == id } }
                local.update { it.copy(selectedId = null) }
            }
            MarkupEvent.Undo -> step(from = undo, to = redo)
            MarkupEvent.Redo -> step(from = redo, to = undo)
            is MarkupEvent.PlaceSignature -> place(event.ink)
            is MarkupEvent.SaveSignature -> viewModelScope.launch {
                val saved = runCatching { saveSignature(event.strokes) }.getOrNull()
                if (saved == null) {
                    // Keystore trouble or an empty pad: still place it on this page, just don't keep it.
                    SignatureInk.from(event.strokes)?.let(::place)
                } else {
                    place(saved.ink)
                }
                refreshSignatures()
            }
            is MarkupEvent.DeleteSignature -> viewModelScope.launch {
                signatures.delete(event.id)
                refreshSignatures()
            }
            MarkupEvent.Save -> viewModelScope.launch {
                runCatching { saveMarkup(pageId, local.value.markup) }
                    .onSuccess {
                        savedState.remove<String>(KEY_MARKUP)
                        effectChannel.trySend(MarkupEffect.Close)
                    }
                    .onFailure { effectChannel.trySend(MarkupEffect.Message(UiText.Res(R.string.error_generic))) }
            }
        }
    }

    /** New signatures land in the lower middle of the page, a third of its width, selected so they can be moved. */
    private fun place(ink: SignatureInk) {
        val item = MarkupItem.Signature(ids.next(), ink, cx = 0.5f, cy = 0.78f, width = 0.34f)
        commit { it + item }
        local.update { it.copy(tool = MarkupTool.SELECT, selectedId = item.id) }
    }

    private fun commit(change: (List<MarkupItem>) -> List<MarkupItem>) {
        val before = local.value.markup
        val after = PageMarkup(change(before.items))
        if (after == before) return
        undo.addLast(before)
        if (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
        publish(after)
    }

    private fun step(from: ArrayDeque<PageMarkup>, to: ArrayDeque<PageMarkup>) {
        val target = from.removeLastOrNull() ?: return
        to.addLast(local.value.markup)
        publish(target)
        local.update { s -> s.copy(selectedId = s.selectedId?.takeIf { id -> target.items.any { it.id == id } }) }
    }

    private fun publish(markup: PageMarkup) {
        savedState[KEY_MARKUP] = PageMarkup.encode(markup) ?: EMPTY_JSON
        local.update { it.copy(markup = markup, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), dirty = true) }
    }

    private suspend fun refreshSignatures() {
        val list = runCatching { signatures.list() }.getOrDefault(emptyList())
        local.update { it.copy(signatures = list.toImmutableList()) }
    }

    fun newId(): String = ids.next()

    private companion object {
        const val KEY_MARKUP = "markup"
        const val EMPTY_JSON = "{}"
        const val MAX_UNDO = 100
    }
}

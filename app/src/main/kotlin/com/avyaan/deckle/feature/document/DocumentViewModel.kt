package com.avyaan.deckle.feature.document

import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.ui.toUiText
import com.avyaan.deckle.domain.page.RenamePageUseCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.document.MoveToTrashUseCase
import com.avyaan.deckle.domain.document.ObserveDocumentUseCase
import com.avyaan.deckle.domain.document.RenameDocumentUseCase
import com.avyaan.deckle.domain.document.SetCoverPageUseCase
import com.avyaan.deckle.domain.ocr.OcrStatus
import com.avyaan.deckle.domain.ocr.RetryOcrUseCase
import com.avyaan.deckle.domain.page.DeletePageUseCase
import com.avyaan.deckle.domain.page.GetDocumentTextUseCase
import com.avyaan.deckle.domain.page.ObservePagesUseCase
import com.avyaan.deckle.domain.page.ReorderPageUseCase
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.navigation.DocumentDest
import com.avyaan.deckle.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PageTileUi(val id: String, val thumbPath: String, val ocr: OcrStatus, val hasSource: Boolean, val name: String? = null)

data class DocumentUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val modifiedAt: Instant? = null,
    val coverPageId: String? = null,
    val pages: ImmutableList<PageTileUi> = persistentListOf(),
    val selection: ImmutableSet<String> = persistentSetOf(),
    val selecting: Boolean = false,
    val secure: Boolean = true,
)

sealed interface DocumentEvent {
    data class Rename(val title: String) : DocumentEvent
    /** Live, during a drag: reorders the local list only. */
    data class Move(val fromIndex: Int, val toIndex: Int) : DocumentEvent
    /** Drop: persists the dragged page's final position (FR-40). */
    data class Drop(val pageId: String) : DocumentEvent
    data class MoveBy(val pageId: String, val delta: Int) : DocumentEvent
    data class PageClicked(val pageId: String) : DocumentEvent
    data class SetCover(val pageId: String) : DocumentEvent
    data class RetryOcr(val pageId: String) : DocumentEvent
    data class RenamePage(val pageId: String, val name: String) : DocumentEvent
    data class DuplicatePage(val pageId: String) : DocumentEvent
    data class CropPage(val pageId: String) : DocumentEvent
    data object CopyText : DocumentEvent
    data object AddPages : DocumentEvent
    data object Delete : DocumentEvent
    data class SetSelecting(val on: Boolean) : DocumentEvent
    data class ToggleSelect(val pageId: String) : DocumentEvent
    data object DeleteSelectedPages : DocumentEvent
}

sealed interface DocumentEffect {
    data class OpenPage(val documentId: String, val pageId: String) : DocumentEffect
    data class OpenCapture(val documentId: String, val afterPageId: String?) : DocumentEffect
    data class OpenRecrop(val pageId: String) : DocumentEffect
    data class CopyToClipboard(val text: String, val message: UiText) : DocumentEffect
    data class Message(val text: UiText) : DocumentEffect
    data object RenameRejected : DocumentEffect
    data object Deleted : DocumentEffect
}

/** Document detail (spec 06 §6). */
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    observeDocument: ObserveDocumentUseCase,
    observePages: ObservePagesUseCase,
    settings: SettingsRepository,
    private val rename: RenameDocumentUseCase,
    private val reorder: ReorderPageUseCase,
    private val setCover: SetCoverPageUseCase,
    private val retryOcr: RetryOcrUseCase,
    private val documentText: GetDocumentTextUseCase,
    private val moveToTrash: MoveToTrashUseCase,
    private val deletePage: DeletePageUseCase,
    private val renamePage: RenamePageUseCase,
    private val duplicatePage: com.avyaan.deckle.domain.page.DuplicatePageUseCase,
) : ViewModel() {

    private val route = savedState.toRoute<DocumentDest>()
    val documentId: String = route.documentId

    /** While dragging, the grid shows this order instead of the database's. */
    private val dragOrder = MutableStateFlow<List<String>?>(null)
    private val local = MutableStateFlow(Local())

    private val effectChannel = Channel<DocumentEffect>(Channel.BUFFERED)
    val effects: Flow<DocumentEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<DocumentUiState> = combine(
        observeDocument(documentId), observePages(documentId), dragOrder, local, settings.settings,
    ) { doc, pages, drag, l, s ->
        val tiles = pages.sortedBy { it.orderIndex }.map { PageTileUi(it.id, it.thumbPath, it.ocr.status, it.sourcePath != null, it.name) }
        val ordered = drag?.let { order -> order.mapNotNull { id -> tiles.firstOrNull { it.id == id } } } ?: tiles
        DocumentUiState(
            loading = false,
            missing = doc == null || doc.deletedAt != null,
            title = doc?.title.orEmpty(),
            modifiedAt = doc?.modifiedAt,
            coverPageId = doc?.coverPageId,
            pages = ordered.toImmutableList(),
            selection = l.selection.toPersistentSet(),
            selecting = l.selecting,
            secure = s.secureScreens,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentUiState())

    init {
        // Deleting the last page deletes the document (here or in the viewer); leave once it's gone.
        // Trash (deletedAt set) isn't handled here: Delete already closes the screen itself.
        viewModelScope.launch {
            observeDocument(documentId).dropWhile { it == null }.first { it == null }
            send(DocumentEffect.Deleted)
        }
    }

    fun onEvent(event: DocumentEvent) {
        when (event) {
            is DocumentEvent.Rename -> viewModelScope.launch {
                if (!rename(documentId, event.title)) send(DocumentEffect.RenameRejected)
            }
            is DocumentEvent.Move -> {
                val order = (dragOrder.value ?: state.value.pages.map { it.id }).toMutableList()
                if (event.fromIndex !in order.indices || event.toIndex !in order.indices) return
                order.add(event.toIndex, order.removeAt(event.fromIndex))
                dragOrder.value = order
            }
            is DocumentEvent.Drop -> {
                val order = dragOrder.value ?: return
                val target = order.indexOf(event.pageId)
                viewModelScope.launch {
                    if (target >= 0) reorder(documentId, event.pageId, target)
                    dragOrder.value = null
                }
            }
            is DocumentEvent.MoveBy -> {
                val ids = state.value.pages.map { it.id }
                val target = (ids.indexOf(event.pageId) + event.delta).coerceIn(0, ids.lastIndex)
                viewModelScope.launch { reorder(documentId, event.pageId, target) }
            }
            is DocumentEvent.PageClicked ->
                if (local.value.selecting) toggle(event.pageId) else send(DocumentEffect.OpenPage(documentId, event.pageId))
            is DocumentEvent.SetCover -> viewModelScope.launch { setCover(documentId, event.pageId) }
            is DocumentEvent.RetryOcr -> viewModelScope.launch { retryOcr(event.pageId) }
            is DocumentEvent.RenamePage -> viewModelScope.launch { renamePage(event.pageId, event.name) }
            is DocumentEvent.DuplicatePage -> viewModelScope.launch {
                val ok = runCatching { duplicatePage(event.pageId) }.getOrNull() != null
                send(DocumentEffect.Message(UiText.Res(if (ok) R.string.page_duplicated else R.string.error_generic)))
            }
            is DocumentEvent.CropPage -> {
                val page = state.value.pages.firstOrNull { it.id == event.pageId } ?: return
                if (page.hasSource) send(DocumentEffect.OpenRecrop(page.id))
                else send(DocumentEffect.Message(DeckleError.OriginalNotSaved.toUiText()))
            }
            DocumentEvent.CopyText -> copyText()
            DocumentEvent.AddPages -> send(DocumentEffect.OpenCapture(documentId, afterPageId = null))
            DocumentEvent.Delete -> viewModelScope.launch {
                moveToTrash(listOf(documentId))
                send(DocumentEffect.Deleted)
            }
            is DocumentEvent.SetSelecting -> local.update { Local(selecting = event.on) }
            is DocumentEvent.ToggleSelect -> toggle(event.pageId)
            DocumentEvent.DeleteSelectedPages -> {
                val ids = local.value.selection
                local.update { Local() }
                viewModelScope.launch {
                    // If that was every page the document is gone too, and the screen closes (see init).
                    val documentRemoved = ids.map { deletePage(it) }.any { it }
                    if (!documentRemoved) send(DocumentEffect.Message(UiText.Res(R.string.review_page_deleted)))
                }
            }
        }
    }

    /** True once per visit when the route asked to open the export sheet (Library → Export). */
    fun consumeOpenExport(): Boolean {
        if (!route.openExport || savedState.get<Boolean>(KEY_EXPORT_CONSUMED) == true) return false
        savedState[KEY_EXPORT_CONSUMED] = true
        return true
    }

    /** Selected page ids in document order, for "export selected". */
    fun selectedInOrder(): List<String> = state.value.pages.map { it.id }.filter { it in local.value.selection }

    private fun toggle(id: String) = local.update {
        val sel = if (id in it.selection) it.selection - id else it.selection + id
        it.copy(selection = sel, selecting = true)
    }

    private fun copyText() {
        viewModelScope.launch {
            val text = documentText(documentId)
            if (text.text.isBlank()) {
                send(DocumentEffect.Message(UiText.Res(R.string.document_no_text)))
                return@launch
            }
            val message = if (text.pagesPending > 0) UiText.Plural(R.plurals.document_text_copied_pending, text.pagesPending)
            else UiText.Res(R.string.document_text_copied)
            send(DocumentEffect.CopyToClipboard(text.text, message))
        }
    }

    private fun send(effect: DocumentEffect) {
        effectChannel.trySend(effect)
    }

    private data class Local(val selecting: Boolean = false, val selection: Set<String> = linkedSetOf())

    private companion object {
        const val KEY_EXPORT_CONSUMED = "export_consumed"
    }
}

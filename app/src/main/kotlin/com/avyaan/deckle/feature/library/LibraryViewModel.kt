package com.avyaan.deckle.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.DocumentCardModel
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.Outcome
import com.avyaan.deckle.domain.document.DocumentSummary
import com.avyaan.deckle.domain.document.LibraryListing
import com.avyaan.deckle.domain.document.MergeDocumentsUseCase
import com.avyaan.deckle.domain.document.MoveToTrashUseCase
import com.avyaan.deckle.domain.document.ObserveLibraryUseCase
import com.avyaan.deckle.domain.document.RestoreDocumentsUseCase
import com.avyaan.deckle.domain.document.SortOrder
import com.avyaan.deckle.domain.document.LibraryFilter
import com.avyaan.deckle.domain.document.PinUseCase
import com.avyaan.deckle.domain.document.SmartCollection
import com.avyaan.deckle.domain.reminder.ExpiryPolicy
import com.avyaan.deckle.domain.tag.CreateTagUseCase
import com.avyaan.deckle.domain.tag.DeleteTagUseCase
import com.avyaan.deckle.domain.tag.EditTagUseCase
import com.avyaan.deckle.domain.tag.TagColor
import com.avyaan.deckle.domain.tag.TagDocumentsUseCase
import com.avyaan.deckle.domain.tag.TagRepository
import com.avyaan.deckle.feature.tags.TagUi
import com.avyaan.deckle.feature.tags.toUi
import java.time.ZoneId
import com.avyaan.deckle.domain.export.ExportArchive
import com.avyaan.deckle.domain.folder.BuildFolderExportUseCase
import com.avyaan.deckle.domain.folder.CreateFolderUseCase
import com.avyaan.deckle.domain.folder.DeleteFoldersUseCase
import com.avyaan.deckle.domain.folder.Folder
import com.avyaan.deckle.domain.folder.FolderTree
import com.avyaan.deckle.domain.folder.MoveToFolderUseCase
import com.avyaan.deckle.domain.folder.RenameFolderUseCase
import com.avyaan.deckle.domain.imaging.ImageInput
import com.avyaan.deckle.domain.scan.AddCaptureUseCase
import com.avyaan.deckle.domain.scan.DiscardScanSessionUseCase
import com.avyaan.deckle.domain.scan.StartScanSessionUseCase
import com.avyaan.deckle.domain.settings.LibraryLayout
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.domain.storage.FindResumableSessionUseCase
import com.avyaan.deckle.navigation.NavResults
import com.avyaan.deckle.ui.UiText
import com.avyaan.deckle.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val content: LibraryContent = LibraryContent.Loading,
    val query: String = "",
    val sort: SortOrder = SortOrder.CREATED_DESC,
    /** NV-04: show only documents with recognised text. */
    val textOnly: Boolean = false,
    /** NV-31: the smart collection or tag the library is narrowed to (across all folders). */
    val collection: SmartCollection? = null,
    val tagFilter: String? = null,
    /** NV-30: every tag, for the chips and pickers. */
    val tags: ImmutableList<TagUi> = persistentListOf(),
    val layout: LibraryLayout = LibraryLayout.GRID,
    val selection: ImmutableSet<String> = persistentSetOf(),
    val folderSelection: ImmutableSet<String> = persistentSetOf(),
    val resumableSession: ResumableSessionUi? = null,
    val highlightDocumentId: String? = null,
    val secure: Boolean = true,
    /** The folder this screen shows; null = the library root. */
    val folderId: String? = null,
    /** Root-first breadcrumb, empty at the root. */
    val path: ImmutableList<Folder> = persistentListOf(),
    val allFolders: ImmutableList<Folder> = persistentListOf(),
) {
    val selecting: Boolean get() = selection.isNotEmpty() || folderSelection.isNotEmpty()
    /** Pin when anything selected is unpinned; otherwise the action unpins. */
    val selectionPinned: Boolean get() = (content as? LibraryContent.Items)?.let { items ->
        val docs = items.documents.filter { it.card.id in selection }
        val folders = items.folders.filter { it.id in folderSelection }
        (docs.isNotEmpty() || folders.isNotEmpty()) && docs.all { it.card.pinned } && folders.all { it.pinned }
    } ?: false
    val selectedCount: Int get() = selection.size + folderSelection.size
    val folderName: String? get() = path.lastOrNull()?.name
}

data class ResumableSessionUi(val sessionId: String, val pageCount: Int)

sealed interface LibraryContent {
    data object Loading : LibraryContent
    /** Nothing anywhere (the root, no folders): the first-run state. */
    data object Empty : LibraryContent
    /** An empty folder. */
    data object FolderEmpty : LibraryContent
    data class NoResults(val query: String) : LibraryContent
    /** The "Has text" filter is on and nothing here has text. */
    data object NoText : LibraryContent
    /** A smart collection or tag has nothing in it yet. */
    data object NoMatches : LibraryContent
    data class Items(val folders: ImmutableList<FolderItem>, val documents: ImmutableList<LibraryItem>) : LibraryContent
}

/** The card model plus the instant the screen formats relative to "today" (formatting needs a Context). */
data class LibraryItem(val card: DocumentCardModel, val date: Instant)

data class FolderItem(val id: String, val name: String, val documentCount: Int, val folderCount: Int, val date: Instant, val pinned: Boolean = false)

sealed interface LibraryEvent {
    data class QueryChanged(val q: String) : LibraryEvent
    data class SortChanged(val s: SortOrder) : LibraryEvent
    data class TextOnlyChanged(val on: Boolean) : LibraryEvent
    /** Null clears it. Choosing a collection clears a tag filter, and the other way round. */
    data class CollectionChanged(val collection: SmartCollection?) : LibraryEvent
    data class TagFilterChanged(val tagId: String?) : LibraryEvent
    data class PinSelected(val pinned: Boolean) : LibraryEvent
    data class TagSelected(val tagId: String) : LibraryEvent
    /** Creates the tag (or finds it by name) and applies it to the selection. */
    data class CreateTagForSelection(val name: String) : LibraryEvent
    data class EditTag(val id: String, val name: String, val color: TagColor) : LibraryEvent
    data class DeleteTag(val id: String) : LibraryEvent
    data class LayoutChanged(val layout: LibraryLayout) : LibraryEvent
    data class CardClicked(val id: String) : LibraryEvent
    data class CardLongPressed(val id: String) : LibraryEvent
    data class FolderClicked(val id: String) : LibraryEvent
    data class FolderLongPressed(val id: String) : LibraryEvent
    data object ClearSelection : LibraryEvent
    /** Documents go to Trash (undoable). Folders follow [keepFolderContents]. */
    data class DeleteSelected(val keepFolderContents: Boolean = true) : LibraryEvent
    data object MergeSelected : LibraryEvent
    data object ExportSelected : LibraryEvent
    data class MoveSelected(val target: String?) : LibraryEvent
    data class CreateFolder(val parentId: String?, val name: String, val moveSelectionInto: Boolean = false) : LibraryEvent
    data class RenameFolder(val id: String, val name: String) : LibraryEvent
    data object UndoDelete : LibraryEvent
    data object ScanClicked : LibraryEvent
    data object ResumeSession : LibraryEvent
    data object DiscardSession : LibraryEvent
    data object Resumed : LibraryEvent
    /** Content URIs chosen in the system photo picker. */
    data class Imported(val uris: List<String>) : LibraryEvent
}

sealed interface LibraryEffect {
    data class OpenCapture(val folderId: String?) : LibraryEffect
    data class OpenReview(val sessionId: String) : LibraryEffect
    data class OpenCrop(val sessionId: String, val captureId: String) : LibraryEffect
    data class OpenDocument(val id: String, val export: Boolean = false) : LibraryEffect
    /** Root-first chain of folder ids; [jump] when the target isn't a direct child (a search hit). */
    data class OpenFolder(val path: List<String>, val jump: Boolean) : LibraryEffect
    data class ExportFolder(val documentIds: List<String>, val archive: ExportArchive) : LibraryEffect
    data class ShowMessage(val text: UiText, val undo: Boolean = false) : LibraryEffect
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    observeLibrary: ObserveLibraryUseCase,
    private val settings: SettingsRepository,
    private val moveToTrash: MoveToTrashUseCase,
    private val restore: RestoreDocumentsUseCase,
    private val merge: MergeDocumentsUseCase,
    private val findResumable: FindResumableSessionUseCase,
    private val discardSession: DiscardScanSessionUseCase,
    private val startSession: StartScanSessionUseCase,
    private val addCapture: AddCaptureUseCase,
    private val createFolder: CreateFolderUseCase,
    private val renameFolder: RenameFolderUseCase,
    private val moveToFolder: MoveToFolderUseCase,
    private val deleteFolders: DeleteFoldersUseCase,
    private val buildFolderExport: BuildFolderExportUseCase,
    tagRepository: TagRepository,
    private val pin: PinUseCase,
    private val createTag: CreateTagUseCase,
    private val editTag: EditTagUseCase,
    private val deleteTag: DeleteTagUseCase,
    private val tagDocuments: TagDocumentsUseCase,
) : ViewModel() {

    /** Set by the folder destination's route argument; absent at the library root. */
    val folderId: String? = savedState.get<String>(KEY_FOLDER)

    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    private val textOnly: StateFlow<Boolean> = savedState.getStateFlow(KEY_TEXT_ONLY, false)
    private val collection: StateFlow<String?> = savedState.getStateFlow(KEY_COLLECTION, null)
    private val tagFilter: StateFlow<String?> = savedState.getStateFlow(KEY_TAG, null)
    private val tags: StateFlow<List<TagUi>> = tagRepository.observeAll()
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val filter: Flow<LibraryFilter> = combine(textOnly, collection, tagFilter) { text, c, t ->
        LibraryFilter(textOnly = text, collection = c?.let { name -> SmartCollection.entries.firstOrNull { it.name == name } }, tagId = t)
    }.distinctUntilChanged()
    private val selection: StateFlow<List<String>> = savedState.getStateFlow(KEY_SELECTION, emptyList())
    private val folderSelection: StateFlow<List<String>> = savedState.getStateFlow(KEY_FOLDER_SELECTION, emptyList())
    private val resumable = MutableStateFlow<ResumableSessionUi?>(null)
    private val highlight: StateFlow<String?> = savedState.getStateFlow(NavResults.HIGHLIGHT_DOCUMENT, null)
    private var lastDeleted: List<String> = emptyList()
    private var latest: LibraryListing? = null

    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)
    val effects: Flow<LibraryEffect> = effectChannel.receiveAsFlow()

    // Typing debounces; clearing the field is instant so the grid comes straight back.
    private val listing: Flow<Pair<String, LibraryListing>> = combine(
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }.map { it.trim() }.distinctUntilChanged(),
        filter,
        ::Pair,
    ).flatMapLatest { (q, f) -> observeLibrary(q, folderId, if (q.isBlank()) f else LibraryFilter.NONE).map { q to it } }

    val state: StateFlow<LibraryUiState> = combine(
        listing, combine(query, filter, ::Pair), combine(selection, folderSelection, ::Pair), combine(settings.settings, tags, ::Pair), combine(resumable, highlight, ::Pair),
    ) { (q, list), (rawQuery, f), (sel, folderSel), (s, allTags), (resume, glow) ->
        latest = list
        // The filter chips sit beside the sort chip, which hides while searching; so do the filters.
        val filtering = f.active && q.isBlank()
        val folders = list.folders.map { FolderItem(it.folder.id, it.folder.name, it.documentCount, it.folderCount, it.folder.dateFor(s.sortOrder), it.folder.pinned) }
        val documents = list.documents
        val colours = allTags.associate { it.id to it.color }
        val now = Instant.now()
        LibraryUiState(
            content = when {
                folders.isNotEmpty() || documents.isNotEmpty() ->
                    LibraryContent.Items(folders.toImmutableList(), documents.map { it.toItem(s.sortOrder, colours, now) }.toImmutableList())
                filtering && f.libraryWide -> LibraryContent.NoMatches
                filtering -> LibraryContent.NoText
                q.isNotBlank() -> LibraryContent.NoResults(q)
                folderId != null -> LibraryContent.FolderEmpty
                else -> LibraryContent.Empty
            },
            query = rawQuery,
            sort = s.sortOrder,
            textOnly = f.textOnly,
            collection = f.collection,
            tagFilter = f.tagId?.takeIf { id -> allTags.any { it.id == id } },
            tags = allTags.toImmutableList(),
            layout = s.libraryLayout,
            selection = sel.toPersistentSet(),
            folderSelection = folderSel.toPersistentSet(),
            // An unfinished scan is offered on the home screen only, not inside every folder.
            resumableSession = resume.takeIf { q.isBlank() && folderId == null },
            highlightDocumentId = glow,
            secure = s.secureScreens,
            folderId = folderId,
            path = list.path.toImmutableList(),
            allFolders = list.allFolders.toImmutableList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState(query = query.value, folderId = folderId))

    init {
        // The "new document" glow fades on its own (spec 05 §4.7).
        viewModelScope.launch {
            highlight.collect { id ->
                if (id != null) {
                    delay(HIGHLIGHT_MS)
                    savedState[NavResults.HIGHLIGHT_DOCUMENT] = null
                }
            }
        }
    }

    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.QueryChanged -> savedState[KEY_QUERY] = event.q
            is LibraryEvent.SortChanged -> viewModelScope.launch { settings.update { it.copy(sortOrder = event.s) } }
            is LibraryEvent.TextOnlyChanged -> savedState[KEY_TEXT_ONLY] = event.on
            is LibraryEvent.CollectionChanged -> {
                savedState[KEY_COLLECTION] = event.collection?.name
                if (event.collection != null) savedState[KEY_TAG] = null
            }
            is LibraryEvent.TagFilterChanged -> {
                savedState[KEY_TAG] = event.tagId
                if (event.tagId != null) savedState[KEY_COLLECTION] = null
            }
            is LibraryEvent.PinSelected -> {
                val docs = selection.value
                val folders = folderSelection.value
                clearSelection()
                viewModelScope.launch {
                    pin(docs, folders, event.pinned)
                    send(LibraryEffect.ShowMessage(UiText.Plural(if (event.pinned) R.plurals.library_pinned else R.plurals.library_unpinned, docs.size + folders.size)))
                }
            }
            is LibraryEvent.TagSelected -> tagSelection(event.tagId)
            is LibraryEvent.CreateTagForSelection -> viewModelScope.launch {
                createTag(event.name)?.let { tagSelection(it.id) }
            }
            is LibraryEvent.EditTag -> viewModelScope.launch {
                if (!editTag(event.id, event.name, event.color)) send(LibraryEffect.ShowMessage(UiText.Res(R.string.tags_name_taken)))
            }
            is LibraryEvent.DeleteTag -> viewModelScope.launch {
                if (tagFilter.value == event.id) savedState[KEY_TAG] = null
                deleteTag(event.id)
            }
            is LibraryEvent.LayoutChanged -> viewModelScope.launch { settings.update { it.copy(libraryLayout = event.layout) } }
            is LibraryEvent.CardClicked ->
                if (selecting()) toggle(KEY_SELECTION, event.id) else send(LibraryEffect.OpenDocument(event.id))
            is LibraryEvent.CardLongPressed -> toggle(KEY_SELECTION, event.id)
            is LibraryEvent.FolderClicked ->
                if (selecting()) toggle(KEY_FOLDER_SELECTION, event.id) else openFolder(event.id)
            is LibraryEvent.FolderLongPressed -> toggle(KEY_FOLDER_SELECTION, event.id)
            LibraryEvent.ClearSelection -> clearSelection()
            is LibraryEvent.DeleteSelected -> deleteSelected(event.keepFolderContents)
            LibraryEvent.MergeSelected -> mergeSelected()
            LibraryEvent.ExportSelected -> exportSelected()
            is LibraryEvent.MoveSelected -> moveSelected(event.target)
            is LibraryEvent.CreateFolder -> viewModelScope.launch {
                val id = createFolder(event.parentId, event.name) ?: return@launch
                if (event.moveSelectionInto) moveSelected(id)
            }
            is LibraryEvent.RenameFolder -> viewModelScope.launch {
                renameFolder(event.id, event.name)
                clearSelection()
            }
            LibraryEvent.UndoDelete -> undoDelete()
            LibraryEvent.ScanClicked -> send(LibraryEffect.OpenCapture(folderId))
            is LibraryEvent.Imported -> import(event.uris)
            LibraryEvent.ResumeSession -> resumable.value?.let { send(LibraryEffect.OpenReview(it.sessionId)) }
            LibraryEvent.DiscardSession -> resumable.value?.let { session ->
                resumable.value = null
                viewModelScope.launch { discardSession(session.sessionId) }
            }
            LibraryEvent.Resumed -> refreshResumable()
        }
    }

    /** Folders the selection can't move into: the selected folders and everything below them. */
    fun moveExclusions(): Set<String> = FolderTree.subtree(folderSelection.value, latest?.allFolders.orEmpty())

    private fun selecting() = selection.value.isNotEmpty() || folderSelection.value.isNotEmpty()

    private fun toggle(key: String, id: String) {
        val current = savedState.get<List<String>>(key).orEmpty()
        savedState[key] = ArrayList(if (id in current) current - id else current + id)
    }

    private fun clearSelection() {
        savedState[KEY_SELECTION] = ArrayList<String>()
        savedState[KEY_FOLDER_SELECTION] = ArrayList<String>()
    }

    private fun openFolder(id: String) {
        // Opened from search results the folder may sit anywhere: hand over the whole chain.
        val path = FolderTree.path(id, latest?.allFolders.orEmpty()).map { it.id }
        val currentPath = latest?.path?.map { it.id }.orEmpty()
        val child = path.isEmpty() || path.dropLast(1) == currentPath
        send(LibraryEffect.OpenFolder(path.ifEmpty { listOf(id) }, jump = !child))
    }

    private fun deleteSelected(keepFolderContents: Boolean) {
        val docs = selection.value
        val folders = folderSelection.value
        if (docs.isEmpty() && folders.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            if (docs.isNotEmpty()) {
                lastDeleted = docs
                moveToTrash(docs)
            }
            if (folders.isNotEmpty()) deleteFolders(folders, keepFolderContents)
            send(
                when {
                    folders.isEmpty() -> LibraryEffect.ShowMessage(UiText.Plural(R.plurals.library_moved_to_trash, docs.size), undo = true)
                    else -> LibraryEffect.ShowMessage(UiText.Plural(R.plurals.library_items_deleted, docs.size + folders.size), undo = docs.isNotEmpty())
                },
            )
        }
    }

    private fun tagSelection(tagId: String) {
        val docs = selection.value
        if (docs.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            tagDocuments(docs, tagId)
            val name = tags.value.firstOrNull { it.id == tagId }?.name.orEmpty()
            send(LibraryEffect.ShowMessage(UiText.Plural(R.plurals.library_tagged, docs.size, listOf(docs.size, name))))
        }
    }

    private fun undoDelete() {
        val ids = lastDeleted
        lastDeleted = emptyList()
        if (ids.isNotEmpty()) viewModelScope.launch { restore(ids) }
    }

    private fun mergeSelected() {
        val ids = selection.value
        if (ids.size < 2 || folderSelection.value.isNotEmpty()) return
        clearSelection()
        viewModelScope.launch {
            when (val result = runCatching { merge(ids) }.getOrElse { Outcome.Failure(DeckleError.Unexpected(it)) }) {
                is Outcome.Success -> {
                    savedState[NavResults.HIGHLIGHT_DOCUMENT] = result.value
                    send(LibraryEffect.OpenDocument(result.value))
                }
                is Outcome.Failure -> send(LibraryEffect.ShowMessage(result.error.toUiText()))
            }
        }
    }

    private fun exportSelected() {
        val docs = selection.value
        val folders = folderSelection.value
        when {
            docs.size == 1 && folders.isEmpty() -> {
                clearSelection()
                send(LibraryEffect.OpenDocument(docs.single(), export = true))
            }
            folders.size == 1 && docs.isEmpty() -> viewModelScope.launch {
                val export = buildFolderExport(folders.single())
                if (export == null) {
                    send(LibraryEffect.ShowMessage(UiText.Res(R.string.folder_export_empty)))
                } else {
                    clearSelection()
                    send(LibraryEffect.ExportFolder(export.documentIds, export.archive))
                }
            }
        }
    }

    private fun moveSelected(target: String?) {
        val docs = selection.value
        val folders = folderSelection.value
        if (docs.isEmpty() && folders.isEmpty()) return
        viewModelScope.launch {
            if (moveToFolder(docs, folders, target)) {
                clearSelection()
                val name = target?.let { id -> latest?.allFolders?.firstOrNull { it.id == id }?.name }
                send(
                    LibraryEffect.ShowMessage(
                        if (name != null) UiText.Plural(R.plurals.library_moved_to, docs.size + folders.size, listOf(docs.size + folders.size, name))
                        else UiText.Plural(R.plurals.library_moved_to_root, docs.size + folders.size),
                    ),
                )
            } else {
                send(LibraryEffect.ShowMessage(UiText.Res(R.string.folder_move_into_itself)))
            }
        }
    }

    private fun refreshResumable() {
        viewModelScope.launch {
            resumable.value = findResumable()?.let { ResumableSessionUi(it.id, it.captures.size) }
        }
    }

    /**
     * Gallery images become a new scan session, exactly like photos taken with the camera: one image opens
     * the crop editor, several open Review (each page can be cropped there). Edge detection runs on import.
     * Imported from inside a folder, the new document lands in that folder.
     */
    private fun import(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val session = startSession(existingId = null, targetDocumentId = null, insertAfterPageId = null, targetFolderId = folderId).id
            val ids = uris.map { addCapture(session, ImageInput.ContentUri(it), liveQuad = null) }
            send(if (ids.size == 1) LibraryEffect.OpenCrop(session, ids.single()) else LibraryEffect.OpenReview(session))
        }
    }

    private fun send(effect: LibraryEffect) {
        effectChannel.trySend(effect)
    }

    private fun DocumentSummary.toItem(sort: SortOrder, colours: Map<String, TagColor>, now: Instant): LibraryItem {
        val date = if (sort == SortOrder.MODIFIED_DESC) modifiedAt else createdAt
        val daysLeft = expiresAt?.let { ExpiryPolicy.daysLeft(it, now, ZoneId.systemDefault()) }?.takeIf { it <= ExpiryPolicy.SOON_DAYS }
        return LibraryItem(
            DocumentCardModel(
                id, title, pageCount, subtitle = "", coverPath = coverThumbPath, snippet = snippet,
                pinned = pinned,
                tagColors = tagIds.mapNotNull { colours[it] }.sortedBy { it.ordinal }.toImmutableList(),
                expiresInDays = daysLeft,
            ),
            date,
        )
    }

    private fun Folder.dateFor(sort: SortOrder) = if (sort == SortOrder.MODIFIED_DESC) modifiedAt else createdAt

    companion object {
        /** Route argument name of [com.avyaan.deckle.navigation.FolderDest]. */
        const val KEY_FOLDER = "folderId"
        private const val KEY_QUERY = "query"
        private const val KEY_TEXT_ONLY = "text_only"
        private const val KEY_COLLECTION = "collection"
        private const val KEY_TAG = "tag"
        private const val KEY_SELECTION = "selection"
        private const val KEY_FOLDER_SELECTION = "folderSelection"
        private const val SEARCH_DEBOUNCE_MS = 200L
        private const val HIGHLIGHT_MS = 2_400L
    }
}

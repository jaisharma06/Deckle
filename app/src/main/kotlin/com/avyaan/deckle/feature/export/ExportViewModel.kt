package com.avyaan.deckle.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.common.DeckleError
import com.avyaan.deckle.domain.common.IdGenerator
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.document.TitleRules
import com.avyaan.deckle.domain.export.EstimateExportSizeUseCase
import com.avyaan.deckle.domain.export.ExportArchive
import com.avyaan.deckle.domain.export.ExportFormat
import com.avyaan.deckle.domain.export.ExportProgress
import com.avyaan.deckle.domain.export.ExportRequest
import com.avyaan.deckle.domain.export.ExportScheduler
import com.avyaan.deckle.domain.export.ExportTarget
import com.avyaan.deckle.domain.export.PageSizePolicy
import com.avyaan.deckle.domain.export.QualityPreset
import com.avyaan.deckle.domain.page.Page
import com.avyaan.deckle.domain.page.PageRepository
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.ui.UiText
import com.avyaan.deckle.ui.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PageScope { ALL, SELECTED }

/** Why the password can't be used yet; null when it's fine. */
enum class PasswordProblem { TOO_SHORT, MISMATCH }

data class ExportUiState(
    val ready: Boolean = false,
    val format: ExportFormat = ExportFormat.PDF,
    val scope: PageScope = PageScope.ALL,
    val totalPages: Int = 0,
    val selectedPages: Int = 0,
    val pageSize: PageSizePolicy = PageSizePolicy.AUTO,
    val quality: QualityPreset = QualityPreset.MEDIUM,
    /** Null while estimating. */
    val estimateBytes: Long? = null,
    val passwordOn: Boolean = false,
    val password: String = "",
    val confirm: String = "",
    val running: ExportProgress.Running? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
    /** Folder export: PDFs packed into one ZIP; the format is fixed. */
    val archive: ExportArchive? = null,
    val documentCount: Int = 0,
) {
    val isPdf: Boolean get() = format == ExportFormat.PDF
    val passwordProblem: PasswordProblem? get() = when {
        !passwordOn || !isPdf -> null
        password.length < MIN_PASSWORD -> PasswordProblem.TOO_SHORT
        password != confirm -> PasswordProblem.MISMATCH
        else -> null
    }
    val canExport: Boolean get() = ready && !busy && passwordProblem == null

    private companion object {
        const val MIN_PASSWORD = 4
    }
}

sealed interface ExportEvent {
    data class SetFormat(val format: ExportFormat) : ExportEvent
    data class SetScope(val scope: PageScope) : ExportEvent
    data class SetPageSize(val size: PageSizePolicy) : ExportEvent
    data class SetQuality(val quality: QualityPreset) : ExportEvent
    data class SetPasswordOn(val on: Boolean) : ExportEvent
    data class SetPassword(val value: String) : ExportEvent
    data class SetConfirm(val value: String) : ExportEvent
    data object Share : ExportEvent
    /** Asks the screen for a destination (CreateDocument for one PDF, a folder otherwise). */
    data object Save : ExportEvent
    data class SaveTo(val target: ExportTarget) : ExportEvent
}

sealed interface ExportEffect {
    data class PickFile(val suggestedName: String, val zip: Boolean = false) : ExportEffect
    data object PickFolder : ExportEffect
    data class ShareFiles(val paths: List<String>, val mimeType: String) : ExportEffect
    data class Message(val text: UiText) : ExportEffect
    data object Close : ExportEffect
}

/**
 * Export sheet (spec 06 §8, FR-50…56). The work itself runs in WorkManager; this ViewModel builds the
 * request, shows the estimate and mirrors progress. The password lives here only as long as the sheet
 * is open and is handed to the scheduler's in-memory store — never saved.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val pages: PageRepository,
    private val settings: SettingsRepository,
    private val estimate: EstimateExportSizeUseCase,
    private val scheduler: ExportScheduler,
    private val ids: IdGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state

    private val effectChannel = Channel<ExportEffect>(Channel.BUFFERED)
    val effects: Flow<ExportEffect> = effectChannel.receiveAsFlow()

    private var documentIds: List<String> = emptyList()
    private var selectedPageIds: List<String> = emptyList()
    private var allPages: List<Page> = emptyList()
    private var archive: ExportArchive? = null
    private var estimateJob: Job? = null

    /** Called when the sheet opens. Safe to call again with the same arguments. */
    fun start(documentIds: List<String>, selectedPageIds: List<String>, archive: ExportArchive? = null) {
        if (this.documentIds == documentIds && this.selectedPageIds == selectedPageIds && _state.value.ready) return
        this.documentIds = documentIds
        this.archive = archive
        this.selectedPageIds = selectedPageIds
        viewModelScope.launch {
            val s = settings.settings.first()
            allPages = documentIds.flatMap { pages.pages(it).sortedBy { p -> p.orderIndex } }
            _state.value = ExportUiState(
                ready = true,
                totalPages = allPages.size,
                selectedPages = selectedPageIds.size,
                scope = if (selectedPageIds.isNotEmpty()) PageScope.SELECTED else PageScope.ALL,
                pageSize = s.defaultPageSize,
                quality = s.defaultQuality,
                archive = archive,
                documentCount = documentIds.size,
            )
            refreshEstimate()
        }
    }

    fun onEvent(event: ExportEvent) {
        when (event) {
            is ExportEvent.SetFormat -> { _state.update { it.copy(format = event.format) }; refreshEstimate() }
            is ExportEvent.SetScope -> { _state.update { it.copy(scope = event.scope) }; refreshEstimate() }
            is ExportEvent.SetPageSize -> _state.update { it.copy(pageSize = event.size) }
            is ExportEvent.SetQuality -> { _state.update { it.copy(quality = event.quality) }; refreshEstimate() }
            is ExportEvent.SetPasswordOn -> _state.update { it.copy(passwordOn = event.on, password = "", confirm = "") }
            is ExportEvent.SetPassword -> _state.update { it.copy(password = event.value) }
            is ExportEvent.SetConfirm -> _state.update { it.copy(confirm = event.value) }
            ExportEvent.Share -> enqueue(ExportTarget.Share)
            ExportEvent.Save -> viewModelScope.launch {
                val s = _state.value
                val zip = archive
                if (zip != null) {
                    send(ExportEffect.PickFile("${zip.name}.zip", zip = true))
                } else if (s.isPdf && documentIds.size == 1) {
                    val title = documents.get(documentIds.single())?.title.orEmpty()
                    send(ExportEffect.PickFile("${TitleRules.fileName(title)}.pdf"))
                } else {
                    send(ExportEffect.PickFolder)
                }
            }
            is ExportEvent.SaveTo -> enqueue(event.target)
        }
    }

    private fun chosenPages(): List<Page> {
        val s = _state.value
        return if (s.scope == PageScope.SELECTED && selectedPageIds.isNotEmpty()) allPages.filter { it.id in selectedPageIds } else allPages
    }

    private fun refreshEstimate() {
        estimateJob?.cancel()
        _state.update { it.copy(estimateBytes = null) }
        estimateJob = viewModelScope.launch {
            val s = _state.value
            val bytes = estimate(chosenPages(), s.quality, s.format)
            _state.update { it.copy(estimateBytes = bytes) }
        }
    }

    private fun enqueue(target: ExportTarget) {
        val s = _state.value
        if (!s.canExport) return
        val exportId = ids.newId()
        val password = if (s.isPdf && s.passwordOn) s.password.toCharArray() else null
        val request = ExportRequest(
            exportId = exportId,
            documentIds = documentIds,
            pageIds = if (s.scope == PageScope.SELECTED && selectedPageIds.isNotEmpty()) selectedPageIds else null,
            format = s.format,
            pageSize = s.pageSize,
            quality = s.quality,
            hasPassword = password != null,
            target = target,
            archive = archive,
        )
        scheduler.enqueue(request, password)
        password?.fill('\u0000')
        // Drop the typed password as soon as it has been handed over.
        _state.update { it.copy(busy = true, password = "", confirm = "") }
        viewModelScope.launch {
            scheduler.observe(exportId)
                .takeWhile { it !is ExportProgress.Succeeded && it !is ExportProgress.Failed }
                .collect { p -> _state.update { it.copy(running = p as? ExportProgress.Running) } }
            when (val final = scheduler.observe(exportId).first { it is ExportProgress.Succeeded || it is ExportProgress.Failed }) {
                is ExportProgress.Succeeded -> {
                    _state.update { it.copy(done = true, running = null) }
                    if (target is ExportTarget.Share) send(ExportEffect.ShareFiles(final.outputs, final.mimeType))
                    else send(ExportEffect.Message(UiText.Res(R.string.export_saved)))
                    send(ExportEffect.Close)
                }
                is ExportProgress.Failed -> {
                    _state.update { it.copy(busy = false, running = null, passwordOn = false) }
                    val e = final.error
                    if (e != DeckleError.Cancelled) send(ExportEffect.Message(e.toUiText()))
                }
                else -> Unit
            }
        }
    }

    private fun send(effect: ExportEffect) {
        effectChannel.trySend(effect)
    }
}

package com.avyaan.deckle.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.R
import com.avyaan.deckle.domain.document.DocumentRepository
import com.avyaan.deckle.domain.export.ExportHistoryRepository
import com.avyaan.deckle.domain.export.ExportRecord
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val records: ImmutableList<ExportRecord> = persistentListOf(),
    val secure: Boolean = true,
)

sealed interface HistoryEvent {
    data class Remove(val id: String) : HistoryEvent
    data object Clear : HistoryEvent
    /** Re-open the export sheet for the record's document (single-document records only). */
    data class ExportAgain(val record: ExportRecord) : HistoryEvent
}

sealed interface HistoryEffect {
    data class OpenExport(val documentId: String) : HistoryEffect
    data class Message(val text: UiText) : HistoryEffect
}

/**
 * Export history (UX addition): what was exported, when, and where to. Sharing and opening the files
 * themselves need a Context and live in the route; this keeps the list and the actions that touch data.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: ExportHistoryRepository,
    private val documents: DocumentRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val effectChannel = Channel<HistoryEffect>(Channel.BUFFERED)
    val effects: Flow<HistoryEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<HistoryUiState> = combine(history.observe(), settings.settings) { records, s ->
        HistoryUiState(loading = false, records = records.toImmutableList(), secure = s.secureScreens)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onEvent(event: HistoryEvent) {
        when (event) {
            is HistoryEvent.Remove -> viewModelScope.launch { history.delete(event.id) }
            HistoryEvent.Clear -> viewModelScope.launch { history.clear() }
            is HistoryEvent.ExportAgain -> viewModelScope.launch {
                val id = event.record.documentIds.singleOrNull() ?: return@launch
                val doc = documents.get(id)
                if (doc == null || doc.deletedAt != null) send(HistoryEffect.Message(UiText.Res(R.string.history_document_gone)))
                else send(HistoryEffect.OpenExport(id))
            }
        }
    }

    private fun send(effect: HistoryEffect) {
        effectChannel.trySend(effect)
    }
}

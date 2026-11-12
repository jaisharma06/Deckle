package com.avyaan.deckle.feature.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.R
import com.avyaan.deckle.designsystem.components.DocumentCardModel
import com.avyaan.deckle.domain.common.Clock
import com.avyaan.deckle.domain.document.DeleteDocumentsPermanentlyUseCase
import com.avyaan.deckle.domain.document.ObserveTrashUseCase
import com.avyaan.deckle.domain.document.PurgeTrashUseCase
import com.avyaan.deckle.domain.document.RestoreDocumentsUseCase
import com.avyaan.deckle.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashItem(val card: DocumentCardModel, val daysLeft: Int)

data class TrashUiState(
    val loading: Boolean = true,
    val items: ImmutableList<TrashItem> = persistentListOf(),
    val selection: ImmutableSet<String> = persistentSetOf(),
)

sealed interface TrashEvent {
    data class Toggle(val id: String) : TrashEvent
    data object ClearSelection : TrashEvent
    data object RestoreSelected : TrashEvent
    data class RestoreOne(val id: String) : TrashEvent
    data object DeleteSelectedForever : TrashEvent
    data object EmptyTrash : TrashEvent
}

sealed interface TrashEffect {
    data class ShowMessage(val text: UiText) : TrashEffect
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    observeTrash: ObserveTrashUseCase,
    private val restore: RestoreDocumentsUseCase,
    private val deleteForever: DeleteDocumentsPermanentlyUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val selection = savedState.getStateFlow(KEY_SELECTION, emptyList<String>())
    private val effectChannel = Channel<TrashEffect>(Channel.BUFFERED)
    val effects: Flow<TrashEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<TrashUiState> = combine(observeTrash(), selection) { docs, sel ->
        val now = clock.now()
        val ids = docs.map { it.id }.toSet()
        TrashUiState(
            loading = false,
            items = docs.map { d ->
                val deleted = d.deletedAt ?: now
                val left = PurgeTrashUseCase.RETENTION.minus(Duration.between(deleted, now))
                TrashItem(
                    DocumentCardModel(d.id, d.title, d.pageCount, subtitle = "", coverPath = d.coverThumbPath, snippet = null),
                    daysLeft = (left.toHours() / 24 + 1).toInt().coerceIn(1, PurgeTrashUseCase.RETENTION.toDays().toInt()),
                )
            }.toImmutableList(),
            selection = sel.filter { it in ids }.toPersistentSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun onEvent(event: TrashEvent) {
        when (event) {
            is TrashEvent.Toggle -> {
                val s = selection.value
                setSelection(if (event.id in s) s - event.id else s + event.id)
            }
            TrashEvent.ClearSelection -> setSelection(emptyList())
            TrashEvent.RestoreSelected -> {
                val ids = selection.value
                setSelection(emptyList())
                restoreIds(ids)
            }
            is TrashEvent.RestoreOne -> restoreIds(listOf(event.id))
            TrashEvent.DeleteSelectedForever -> {
                val ids = selection.value
                setSelection(emptyList())
                viewModelScope.launch { deleteForever(ids) }
            }
            TrashEvent.EmptyTrash -> {
                val ids = state.value.items.map { it.card.id }
                setSelection(emptyList())
                viewModelScope.launch { deleteForever(ids) }
            }
        }
    }

    private fun restoreIds(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            restore(ids)
            effectChannel.send(TrashEffect.ShowMessage(UiText.Res(R.string.trash_restored)))
        }
    }

    private fun setSelection(ids: List<String>) {
        savedState[KEY_SELECTION] = ArrayList(ids)
    }

    private companion object {
        const val KEY_SELECTION = "selection"
    }
}

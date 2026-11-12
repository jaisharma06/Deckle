package com.avyaan.deckle.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avyaan.deckle.domain.settings.AppSettings
import com.avyaan.deckle.domain.settings.SettingsRepository
import com.avyaan.deckle.domain.storage.ClearCacheUseCase
import com.avyaan.deckle.domain.storage.GetStorageUsageUseCase
import com.avyaan.deckle.domain.storage.StorageUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** Null until measured (the figures fade in). */
    val storage: StorageUsage? = null,
)

sealed interface SettingsEvent {
    /** One event for every preference: the screen says what changes, the ViewModel persists it. */
    data class Update(val transform: (AppSettings) -> AppSettings) : SettingsEvent
    data object ClearCache : SettingsEvent
    data object Refresh : SettingsEvent
}

sealed interface SettingsEffect {
    data object CacheCleared : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val usage: GetStorageUsageUseCase,
    private val clearCache: ClearCacheUseCase,
) : ViewModel() {

    private val storage = MutableStateFlow<StorageUsage?>(null)
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(repository.settings, storage, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refresh()
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Update -> viewModelScope.launch { repository.update(event.transform) }
            SettingsEvent.ClearCache -> viewModelScope.launch {
                clearCache()
                storage.value = usage()
                effectChannel.send(SettingsEffect.CacheCleared)
            }
            SettingsEvent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch { storage.value = usage() }
    }
}

package com.imagenext.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Photos tab state orchestration.
 *
 * Provides paged timeline data and sync state for the Photos screen.
 * Cached paging data survives configuration changes via [cachedIn].
 */
class PhotosViewModel(
    timelineRepository: TimelineRepository,
    private val syncOrchestrator: SyncOrchestrator,
) : ViewModel() {

    /** Paged timeline of media items, cached across config changes. */
    val timelineItems: Flow<PagingData<MediaItem>> =
        timelineRepository.getTimelinePaged()
            .cachedIn(viewModelScope)

    /** Current sync state for progress/status display. */
    private val _syncState = MutableStateFlow(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        observeSync()
    }

    private fun observeSync() {
        viewModelScope.launch {
            syncOrchestrator.observeSyncState().collect { state ->
                _syncState.value = state
            }
        }
    }

    /** Retries a failed sync operation. */
    fun retrySync() {
        syncOrchestrator.retrySync()
    }
}

/**
 * Factory for [PhotosViewModel] to support creation through
 * `viewModel(factory = ...)` at the Photos route.
 */
class PhotosViewModelFactory(
    private val timelineRepository: TimelineRepository,
    private val syncOrchestrator: SyncOrchestrator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PhotosViewModel(
            timelineRepository = timelineRepository,
            syncOrchestrator = syncOrchestrator,
        ) as T
    }
}

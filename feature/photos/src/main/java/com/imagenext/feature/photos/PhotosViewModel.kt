package com.imagenext.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    /** Paged timeline items with in-stream date headers, cached across config changes. */
    val timelineItems: Flow<PagingData<TimelineItem>> =
        timelineRepository.getTimelinePaged()
            .map { pagingData ->
                val dateContext = TimelineDateContext()
                pagingData
                    .map { mediaItem -> TimelineItem.Photo(mediaItem) as TimelineItem }
                    .insertSeparators { before, after ->
                        val nextPhoto = after as? TimelineItem.Photo ?: return@insertSeparators null
                        val previousPhoto = before as? TimelineItem.Photo

                        val nextDate = nextPhoto.mediaItem.resolveTimelineDate(dateContext)
                        val previousDate = previousPhoto?.mediaItem?.resolveTimelineDate(dateContext)

                        if (before == null || timelineGroupKey(previousDate) != timelineGroupKey(nextDate)) {
                            TimelineItem.Header(
                                label = formatTimelineHeaderLabel(nextDate, dateContext),
                                date = nextDate,
                            )
                        } else {
                            null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    /** Current sync state for progress/status display. */
    private val _syncState = MutableStateFlow(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        observeSync()
        triggerThumbnailBackfillIfNeeded()
    }

    private fun observeSync() {
        viewModelScope.launch {
            syncOrchestrator.observeSyncState().collect { syncState ->
                _syncState.value = syncState
            }
        }
    }

    private fun triggerThumbnailBackfillIfNeeded() {
        viewModelScope.launch {
            // Let startup interactions settle before background thumbnail backfill.
            delay(INITIAL_BACKFILL_DELAY_MS)
            syncOrchestrator.scheduleThumbnailBackfillIfNeeded()
        }
    }

    /** Retries sync or re-queues partial thumbnail failures on demand. */
    fun retrySync() {
        viewModelScope.launch {
            if (_syncState.value == SyncState.Partial) {
                syncOrchestrator.scheduleThumbnailBackfillIfNeeded(requeueExhaustedFailures = true)
            } else {
                syncOrchestrator.retrySync()
            }
        }
    }

    private companion object {
        const val INITIAL_BACKFILL_DELAY_MS = 2500L
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

package com.imagenext.feature.photos

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.imagenext.core.data.AddMediaResult
import com.imagenext.core.data.AlbumPickerItem
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.AlbumWriteResult
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val albumRepository: AlbumRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var lastForegroundSyncRequestElapsedMs = 0L
    private var suppressNextForegroundSync: Boolean =
        savedStateHandle[KEY_SUPPRESS_NEXT_FOREGROUND_SYNC] ?: false

    /** Paged timeline items with in-stream date headers, cached across config changes. */
    val timelineItems: Flow<PagingData<TimelineItem>> =
        timelineRepository.getTimelinePaged()
            .map { pagingData ->
                val dateContext = TimelineDateContext()
                pagingData
                    .map { mediaItem -> TimelineItem.Media(mediaItem) as TimelineItem }
                    .insertSeparators { before, after ->
                        val nextMedia = after as? TimelineItem.Media ?: return@insertSeparators null
                        val previousMedia = before as? TimelineItem.Media

                        val nextDate = nextMedia.mediaItem.resolveTimelineDate(dateContext)
                        val previousDate = previousMedia?.mediaItem?.resolveTimelineDate(dateContext)

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

    private val _albumPickerItems = MutableStateFlow<List<AlbumPickerItem>>(emptyList())
    val albumPickerItems: StateFlow<List<AlbumPickerItem>> = _albumPickerItems.asStateFlow()

    private val _pendingReturnAnchor = MutableStateFlow(loadSavedReturnAnchor())
    val pendingReturnAnchor: StateFlow<ReturnAnchor?> = _pendingReturnAnchor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        observeSync()
        observeAlbumPicker()
        onPhotosForegrounded()
        triggerThumbnailBackfillIfNeeded()
        enableAutoSync()
        triggerBackupDetectorOnLaunch()
    }

    private fun observeSync() {
        viewModelScope.launch {
            syncOrchestrator.observeSyncState().collect { syncState ->
                _syncState.value = syncState
            }
        }
    }

    fun onPhotosForegrounded() {
        if (suppressNextForegroundSync) {
            suppressNextForegroundSync = false
            savedStateHandle[KEY_SUPPRESS_NEXT_FOREGROUND_SYNC] = false
            _pendingReturnAnchor.value = _pendingReturnAnchor.value?.copy(readyToApply = true).also {
                persistReturnAnchor(it)
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundSyncRequestElapsedMs < FOREGROUND_SYNC_MIN_INTERVAL_MS) return
        lastForegroundSyncRequestElapsedMs = now

        // Perform a foreground freshness check when Photos is entered/resumed.
        // KEEP policy avoids replacing an already running metadata sync.
        syncOrchestrator.requestSyncNow()
    }

    fun onMediaOpened(
        remotePath: String,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        suppressNextForegroundSync = true
        savedStateHandle[KEY_SUPPRESS_NEXT_FOREGROUND_SYNC] = true
        _pendingReturnAnchor.value = ReturnAnchor(
            remotePath = remotePath,
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            readyToApply = false,
        ).also { persistReturnAnchor(it) }
    }

    fun onReturnAnchorApplied() {
        _pendingReturnAnchor.value = null
        persistReturnAnchor(null)
    }

    private fun loadSavedReturnAnchor(): ReturnAnchor? {
        val remotePath = savedStateHandle.get<String>(KEY_RETURN_ANCHOR_REMOTE_PATH) ?: return null
        val index = savedStateHandle.get<Int>(KEY_RETURN_ANCHOR_FIRST_VISIBLE_INDEX) ?: 0
        val offset = savedStateHandle.get<Int>(KEY_RETURN_ANCHOR_FIRST_VISIBLE_OFFSET) ?: 0
        val ready = savedStateHandle.get<Boolean>(KEY_RETURN_ANCHOR_READY) ?: false
        return ReturnAnchor(
            remotePath = remotePath,
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset,
            readyToApply = ready,
        )
    }

    private fun persistReturnAnchor(anchor: ReturnAnchor?) {
        if (anchor == null) {
            savedStateHandle.remove<String>(KEY_RETURN_ANCHOR_REMOTE_PATH)
            savedStateHandle.remove<Int>(KEY_RETURN_ANCHOR_FIRST_VISIBLE_INDEX)
            savedStateHandle.remove<Int>(KEY_RETURN_ANCHOR_FIRST_VISIBLE_OFFSET)
            savedStateHandle.remove<Boolean>(KEY_RETURN_ANCHOR_READY)
            return
        }

        savedStateHandle[KEY_RETURN_ANCHOR_REMOTE_PATH] = anchor.remotePath
        savedStateHandle[KEY_RETURN_ANCHOR_FIRST_VISIBLE_INDEX] = anchor.firstVisibleItemIndex
        savedStateHandle[KEY_RETURN_ANCHOR_FIRST_VISIBLE_OFFSET] = anchor.firstVisibleItemScrollOffset
        savedStateHandle[KEY_RETURN_ANCHOR_READY] = anchor.readyToApply
    }

    private fun triggerThumbnailBackfillIfNeeded() {
        viewModelScope.launch {
            // Let startup interactions settle before background thumbnail backfill.
            delay(INITIAL_BACKFILL_DELAY_MS)
            syncOrchestrator.scheduleThumbnailBackfillIfNeeded()
        }
    }

    private fun enableAutoSync() {
        syncOrchestrator.ensureAutoSyncScheduled()
    }

    private fun triggerBackupDetectorOnLaunch() {
        syncOrchestrator.kickBackupDetectorOnLaunch()
    }

    private fun observeAlbumPicker() {
        viewModelScope.launch {
            albumRepository.observeAlbumPicker().collect { items ->
                _albumPickerItems.value = items
            }
        }
    }

    /** Pull-to-refresh entrypoint for immediate server-side change detection. */
    fun refreshPhotos() {
        // User-initiated refresh should replace any stale/infinite in-flight run.
        syncOrchestrator.retrySync()
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

    fun addMediaToAlbum(albumId: Long, mediaRemotePath: String) {
        viewModelScope.launch {
            when (albumRepository.addMediaToAlbum(albumId, mediaRemotePath)) {
                AddMediaResult.Added -> _messages.tryEmit("Added to album")
                AddMediaResult.AlreadyInAlbum -> _messages.tryEmit("Already in album")
                AddMediaResult.AlbumNotFound -> _messages.tryEmit("Album not found")
                AddMediaResult.MediaNotFound -> _messages.tryEmit("Photo not found")
            }
        }
    }

    fun createAlbumAndAdd(albumName: String, mediaRemotePath: String) {
        viewModelScope.launch {
            when (val createResult = albumRepository.createAlbum(albumName)) {
                is AlbumWriteResult.Success -> addMediaToAlbum(createResult.albumId, mediaRemotePath)
                is AlbumWriteResult.EmptyName -> _messages.tryEmit("Album name cannot be empty")
                is AlbumWriteResult.DuplicateName -> addMediaToAlbum(createResult.existingAlbumId, mediaRemotePath)
                is AlbumWriteResult.NotFound -> _messages.tryEmit("Album not found")
            }
        }
    }

    private companion object {
        const val INITIAL_BACKFILL_DELAY_MS = 2500L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 45_000L
        const val KEY_SUPPRESS_NEXT_FOREGROUND_SYNC = "photos.suppress_next_foreground_sync"
        const val KEY_RETURN_ANCHOR_REMOTE_PATH = "photos.return_anchor.remote_path"
        const val KEY_RETURN_ANCHOR_FIRST_VISIBLE_INDEX = "photos.return_anchor.first_visible_index"
        const val KEY_RETURN_ANCHOR_FIRST_VISIBLE_OFFSET = "photos.return_anchor.first_visible_offset"
        const val KEY_RETURN_ANCHOR_READY = "photos.return_anchor.ready"
    }
}

data class ReturnAnchor(
    val remotePath: String,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val readyToApply: Boolean,
)

/**
 * Factory for [PhotosViewModel] to support creation through
 * `viewModel(factory = ...)` at the Photos route.
 */
class PhotosViewModelFactory(
    private val timelineRepository: TimelineRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val albumRepository: AlbumRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PhotosViewModel(
            timelineRepository = timelineRepository,
            syncOrchestrator = syncOrchestrator,
            albumRepository = albumRepository,
            savedStateHandle = extras.createSavedStateHandle(),
        ) as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PhotosViewModel(
            timelineRepository = timelineRepository,
            syncOrchestrator = syncOrchestrator,
            albumRepository = albumRepository,
            savedStateHandle = SavedStateHandle(),
        ) as T
    }
}

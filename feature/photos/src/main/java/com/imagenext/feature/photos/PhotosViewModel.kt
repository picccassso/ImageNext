package com.imagenext.feature.photos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.imagenext.core.data.AlbumPickerItem
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.AlbumWriteResult
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * Photos tab state orchestration.
 *
 * Provides paged timeline data and sync state for the Photos screen.
 * Cached paging data survives configuration changes via [cachedIn].
 */
class PhotosViewModel(
    appContext: Context,
    private val timelineRepository: TimelineRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val albumRepository: AlbumRepository,
    private val serverReachabilityProbe: suspend () -> Boolean,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var lastForegroundSyncRequestElapsedMs = 0L
    private var suppressNextForegroundSync: Boolean =
        savedStateHandle[KEY_SUPPRESS_NEXT_FOREGROUND_SYNC] ?: false

    private var pendingSelectAllConfirmation: SelectAllConfirmation? = null

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isDeviceOffline = MutableStateFlow(resolveInitialOfflineState())
    // Default to false initially, let the first probe update it to true.
    private val _isServerReachable = MutableStateFlow(false)

    val isOffline: StateFlow<Boolean> = combine(
        _isDeviceOffline,
        _isServerReachable,
    ) { deviceOffline, serverReachable ->
        deviceOffline || !serverReachable
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _isDeviceOffline.value || !_isServerReachable.value,
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            setDeviceOffline(!hasActiveNetwork())
        }

        override fun onLost(network: Network) {
            setDeviceOffline(!hasActiveNetwork())
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            if (network == connectivityManager.activeNetwork) {
                setDeviceOffline(!capabilities.hasInternetCapability())
            } else {
                setDeviceOffline(!hasActiveNetwork())
            }
        }
    }

    private fun resolveInitialOfflineState(): Boolean = !hasActiveNetwork()

    private fun hasActiveNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasInternetCapability()
    }

    private fun NetworkCapabilities.hasInternetCapability(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setDeviceOffline(isOffline: Boolean) {
        _isDeviceOffline.value = isOffline
    }

    private fun setServerReachable(isReachable: Boolean) {
        _isServerReachable.value = isReachable
    }

    private suspend fun refreshServerReachabilityNow(): Boolean {
        if (_isDeviceOffline.value) {
            setServerReachable(false)
            return false
        }

        val isReachable = try {
            withTimeoutOrNull(5000L) {
                serverReachabilityProbe()
            } ?: false
        } catch (_: Exception) {
            false
        }
        setServerReachable(isReachable)
        return isReachable
    }

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

    private val _selectionState = MutableStateFlow(
        PhotosSelectionState(maxSelectableCount = SELECTION_CAP),
    )
    val selectionState: StateFlow<PhotosSelectionState> = _selectionState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _uiEvents = MutableSharedFlow<PhotosUiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<PhotosUiEvent> = _uiEvents.asSharedFlow()

    init {
        registerNetworkCallback()
        observeServerReachability()
        observeSync()
        observeAlbumPicker()
        onPhotosForegrounded()
        triggerThumbnailBackfillIfNeeded()
        enableAutoSync()
        triggerBackupDetectorOnLaunch()
    }

    private fun registerNetworkCallback() {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun observeServerReachability() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshServerReachabilityNow()
                delay(SERVER_REACHABILITY_REFRESH_MS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectivityManager.unregisterNetworkCallback(networkCallback)
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

        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshServerReachabilityNow()) return@launch

            val now = SystemClock.elapsedRealtime()
            if (now - lastForegroundSyncRequestElapsedMs < FOREGROUND_SYNC_MIN_INTERVAL_MS) return@launch
            lastForegroundSyncRequestElapsedMs = now

            // Perform a foreground freshness check when Photos is entered/resumed.
            // KEEP policy avoids replacing an already running metadata sync.
            syncOrchestrator.requestSyncNow()
        }
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

    fun onMediaLongPressed(remotePath: String) {
        if (remotePath.isBlank()) return
        viewModelScope.launch {
            val selectableCount = refreshSelectableCount()
            when (
                val mutation = selectFromLongPress(
                    state = _selectionState.value,
                    remotePath = remotePath,
                    selectableCount = selectableCount,
                    maxSelectableCount = SELECTION_CAP,
                )
            ) {
                is SelectionMutation.Updated -> _selectionState.value = mutation.state
                SelectionMutation.CapExceeded -> _messages.tryEmit("You can select up to $SELECTION_CAP items")
                SelectionMutation.NoChange -> Unit
            }
        }
    }

    fun onMediaTappedInSelection(remotePath: String) {
        if (remotePath.isBlank()) return
        if (!_selectionState.value.isSelectionMode) return
        viewModelScope.launch {
            val selectableCount = refreshSelectableCount()
            when (
                val mutation = toggleFromTap(
                    state = _selectionState.value,
                    remotePath = remotePath,
                    selectableCount = selectableCount,
                    maxSelectableCount = SELECTION_CAP,
                )
            ) {
                is SelectionMutation.Updated -> _selectionState.value = mutation.state
                SelectionMutation.CapExceeded -> _messages.tryEmit("You can select up to $SELECTION_CAP items")
                SelectionMutation.NoChange -> Unit
            }
        }
    }

    fun onSelectAllToggle() {
        viewModelScope.launch {
            val totalCount = withContext(Dispatchers.IO) { timelineRepository.getMediaCount() }
            val selectableCount = min(totalCount, SELECTION_CAP)
            val current = _selectionState.value

            if (selectableCount <= 0) {
                exitSelectionMode()
                return@launch
            }

            if (shouldClearAllFromToggle(current, selectableCount)) {
                // Toggle behavior: if everything selectable is already selected, clear all.
                exitSelectionMode()
                return@launch
            }

            if (totalCount > SELECTION_CAP) {
                pendingSelectAllConfirmation = SelectAllConfirmation(totalCount = totalCount)
                _selectionState.value = current.copy(
                    isSelectionMode = true,
                    selectableCount = selectableCount,
                    maxSelectableCount = SELECTION_CAP,
                )
                _uiEvents.tryEmit(
                    PhotosUiEvent.ConfirmSelectAll(
                        totalCount = totalCount,
                        cappedCount = SELECTION_CAP,
                    )
                )
                return@launch
            }

            val allRemotePaths = withContext(Dispatchers.IO) {
                timelineRepository.getTimelineRemotePaths(selectableCount)
            }
            _selectionState.value = applySelectAllState(
                remotePaths = allRemotePaths,
                selectableCount = selectableCount,
                maxSelectableCount = SELECTION_CAP,
            )
        }
    }

    fun confirmSelectAll() {
        val pending = pendingSelectAllConfirmation ?: return
        pendingSelectAllConfirmation = null
        viewModelScope.launch {
            val allRemotePaths = withContext(Dispatchers.IO) {
                timelineRepository.getTimelineRemotePaths(SELECTION_CAP)
            }
            _selectionState.value = applySelectAllState(
                remotePaths = allRemotePaths,
                selectableCount = min(pending.totalCount, SELECTION_CAP),
                maxSelectableCount = SELECTION_CAP,
            )
        }
    }

    fun cancelSelectAllPrompt() {
        pendingSelectAllConfirmation = null
    }

    fun exitSelectionMode() {
        pendingSelectAllConfirmation = null
        _selectionState.value = PhotosSelectionState(maxSelectableCount = SELECTION_CAP)
    }

    fun createAlbumForSelectionPicker(albumName: String) {
        viewModelScope.launch {
            when (val createResult = albumRepository.createAlbum(albumName)) {
                is AlbumWriteResult.Success -> {
                    _messages.tryEmit("Album created")
                    _uiEvents.tryEmit(PhotosUiEvent.AutoSelectAlbum(createResult.albumId))
                }
                is AlbumWriteResult.EmptyName -> {
                    _messages.tryEmit("Album name cannot be empty")
                }
                is AlbumWriteResult.DuplicateName -> {
                    if (createResult.existingAlbumId > 0L) {
                        _messages.tryEmit("Album exists. Selected existing album")
                        _uiEvents.tryEmit(PhotosUiEvent.AutoSelectAlbum(createResult.existingAlbumId))
                    } else {
                        _messages.tryEmit("Album name already exists")
                    }
                }
                is AlbumWriteResult.NotFound -> {
                    _messages.tryEmit("Album not found")
                }
            }
        }
    }

    fun applySelectionToAlbums(targets: List<AlbumApplyTarget>) {
        val mediaSnapshot = _selectionState.value.selectedRemotePaths.toList()
        val targetSnapshot = targets.distinctBy { it.albumId }

        if (mediaSnapshot.isEmpty()) {
            _messages.tryEmit("No items selected")
            return
        }
        if (targetSnapshot.isEmpty()) {
            _messages.tryEmit("Select at least one album")
            return
        }

        // Background fire-and-forget: clear selection state immediately.
        exitSelectionMode()
        _messages.tryEmit("Adding to albums...")

        viewModelScope.launch(Dispatchers.IO) {
            val outcomes = targetSnapshot.map { target ->
                AlbumApplySummaryRow(
                    albumName = target.albumName,
                    result = albumRepository.addMediaToAlbumBulk(target.albumId, mediaSnapshot),
                )
            }
            _messages.tryEmit(buildAlbumApplySummaryMessage(outcomes, visibleLimit = BULK_RESULT_VISIBLE_LIMIT))
        }
    }

    private suspend fun refreshSelectableCount(): Int {
        val totalCount = withContext(Dispatchers.IO) { timelineRepository.getMediaCount() }
        val selectableCount = min(totalCount, SELECTION_CAP)
        _selectionState.update { current ->
            current.copy(
                selectableCount = selectableCount,
                maxSelectableCount = SELECTION_CAP,
            )
        }
        return selectableCount
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
        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshServerReachabilityNow()) return@launch
            // User-initiated refresh should replace any stale/infinite in-flight run.
            syncOrchestrator.retrySync()
        }
    }

    /** Retries sync or re-queues partial thumbnail failures on demand. */
    fun retrySync() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshServerReachabilityNow()) return@launch
            if (_syncState.value == SyncState.Partial) {
                syncOrchestrator.scheduleThumbnailBackfillIfNeeded(requeueExhaustedFailures = true)
            }
            // Always trigger a metadata sync so remote additions/deletions reconcile immediately.
            syncOrchestrator.retrySync()
        }
    }

    private companion object {
        const val INITIAL_BACKFILL_DELAY_MS = 2500L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 45_000L
        const val SERVER_REACHABILITY_REFRESH_MS = 10_000L
        const val KEY_SUPPRESS_NEXT_FOREGROUND_SYNC = "photos.suppress_next_foreground_sync"
        const val KEY_RETURN_ANCHOR_REMOTE_PATH = "photos.return_anchor.remote_path"
        const val KEY_RETURN_ANCHOR_FIRST_VISIBLE_INDEX = "photos.return_anchor.first_visible_index"
        const val KEY_RETURN_ANCHOR_FIRST_VISIBLE_OFFSET = "photos.return_anchor.first_visible_offset"
        const val KEY_RETURN_ANCHOR_READY = "photos.return_anchor.ready"
        const val SELECTION_CAP = 500
        const val BULK_RESULT_VISIBLE_LIMIT = 2
    }
}

data class PhotosSelectionState(
    val isSelectionMode: Boolean = false,
    val selectedRemotePaths: Set<String> = emptySet(),
    val selectableCount: Int = 0,
    val maxSelectableCount: Int,
) {
    val selectedCount: Int
        get() = selectedRemotePaths.size

    val isAllSelectableSelected: Boolean
        get() = selectableCount > 0 && selectedCount == selectableCount
}

sealed interface PhotosUiEvent {
    data class ConfirmSelectAll(
        val totalCount: Int,
        val cappedCount: Int,
    ) : PhotosUiEvent

    data class AutoSelectAlbum(
        val albumId: Long,
    ) : PhotosUiEvent
}

data class AlbumApplyTarget(
    val albumId: Long,
    val albumName: String,
)

private data class SelectAllConfirmation(
    val totalCount: Int,
)

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
    private val appContext: Context,
    private val timelineRepository: TimelineRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val albumRepository: AlbumRepository,
    private val serverReachabilityProbe: suspend () -> Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PhotosViewModel(
            appContext = appContext,
            timelineRepository = timelineRepository,
            syncOrchestrator = syncOrchestrator,
            albumRepository = albumRepository,
            serverReachabilityProbe = serverReachabilityProbe,
            savedStateHandle = extras.createSavedStateHandle(),
        ) as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PhotosViewModel(
            appContext = appContext,
            timelineRepository = timelineRepository,
            syncOrchestrator = syncOrchestrator,
            albumRepository = albumRepository,
            serverReachabilityProbe = serverReachabilityProbe,
            savedStateHandle = SavedStateHandle(),
        ) as T
    }
}

package com.imagenext.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.AlbumWriteResult
import com.imagenext.core.data.RemoveMediaBulkResult
import com.imagenext.core.data.RemoveMediaResult
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

data class AlbumDetailState(
    val albumId: Long,
    val title: String = "Album",
    val exists: Boolean = true,
    val isSystem: Boolean = false,
)

sealed interface AlbumDetailEvent {
    data class Message(val text: String) : AlbumDetailEvent
    data object AlbumDeleted : AlbumDetailEvent
}

data class AlbumDetailSelectionState(
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

class AlbumDetailViewModel(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    val mediaItems: Flow<PagingData<MediaItem>> =
        albumRepository.getAlbumMediaPaged(albumId).cachedIn(viewModelScope)

    private val _state = MutableStateFlow(AlbumDetailState(albumId = albumId))
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<AlbumDetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AlbumDetailEvent> = _events.asSharedFlow()

    private val _selectionState = MutableStateFlow(
        AlbumDetailSelectionState(maxSelectableCount = SELECTION_CAP),
    )
    val selectionState: StateFlow<AlbumDetailSelectionState> = _selectionState.asStateFlow()

    init {
        observeAlbum()
    }

    private fun observeAlbum() {
        viewModelScope.launch {
            albumRepository.observeAlbums().collect { albums ->
                val album = albums.firstOrNull { it.id == albumId }
                if (album == null) {
                    _state.value = _state.value.copy(exists = false)
                } else {
                    _state.value = _state.value.copy(
                        exists = true,
                        title = album.displayName,
                        isSystem = album.isSystem,
                    )
                }
            }
        }
    }

    fun renameAlbum(newName: String) {
        if (_state.value.isSystem) {
            _events.tryEmit(AlbumDetailEvent.Message("This album cannot be renamed"))
            return
        }
        viewModelScope.launch {
            when (val result = albumRepository.renameAlbum(albumId, newName)) {
                is AlbumWriteResult.Success -> _events.tryEmit(AlbumDetailEvent.Message("Album renamed"))
                is AlbumWriteResult.EmptyName -> _events.tryEmit(AlbumDetailEvent.Message("Album name cannot be empty"))
                is AlbumWriteResult.DuplicateName -> _events.tryEmit(AlbumDetailEvent.Message("Album name already exists"))
                is AlbumWriteResult.NotFound -> _events.tryEmit(AlbumDetailEvent.AlbumDeleted)
            }
        }
    }

    fun deleteAlbum() {
        if (_state.value.isSystem) {
            _events.tryEmit(AlbumDetailEvent.Message("This album cannot be deleted"))
            return
        }
        viewModelScope.launch {
            albumRepository.deleteAlbum(albumId)
            _events.tryEmit(AlbumDetailEvent.AlbumDeleted)
        }
    }

    fun removeMediaFromAlbum(remotePath: String) {
        if (_state.value.isSystem) {
            _events.tryEmit(AlbumDetailEvent.Message("Items are managed automatically in this album"))
            return
        }
        viewModelScope.launch {
            when (albumRepository.removeMediaFromAlbum(albumId, remotePath)) {
                RemoveMediaResult.Removed -> _events.tryEmit(AlbumDetailEvent.Message("Removed from album"))
                RemoveMediaResult.NotInAlbum -> _events.tryEmit(AlbumDetailEvent.Message("Item is not in this album"))
            }
        }
    }

    fun onMediaLongPressed(remotePath: String) {
        if (_state.value.isSystem) {
            _events.tryEmit(AlbumDetailEvent.Message("Items are managed automatically in this album"))
            return
        }
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
                is AlbumSelectionMutation.Updated -> _selectionState.value = mutation.state
                AlbumSelectionMutation.CapExceeded -> _events.tryEmit(AlbumDetailEvent.Message("You can select up to $SELECTION_CAP items"))
                AlbumSelectionMutation.NoChange -> Unit
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
                is AlbumSelectionMutation.Updated -> _selectionState.value = mutation.state
                AlbumSelectionMutation.CapExceeded -> _events.tryEmit(AlbumDetailEvent.Message("You can select up to $SELECTION_CAP items"))
                AlbumSelectionMutation.NoChange -> Unit
            }
        }
    }

    fun onSelectAllToggle() {
        viewModelScope.launch {
            val current = _selectionState.value

            if (shouldClearAllFromToggle(current, current.selectableCount)) {
                exitSelectionMode()
                return@launch
            }

            // For albums, we can't easily get all remote paths without loading all pages
            // So we'll just select all currently loaded items we can find
            // This is a simplified approach - select up to cap from what's available
            _selectionState.value = current.copy(
                isSelectionMode = true,
                selectedRemotePaths = emptySet(), // Will be filled by UI observing paging items
            )
            _events.tryEmit(AlbumDetailEvent.Message("Select items individually"))
        }
    }

    fun exitSelectionMode() {
        _selectionState.value = AlbumDetailSelectionState(maxSelectableCount = SELECTION_CAP)
    }

    fun removeSelectedMedia() {
        val selectedSnapshot = _selectionState.value.selectedRemotePaths.toList()
        if (selectedSnapshot.isEmpty()) {
            _events.tryEmit(AlbumDetailEvent.Message("No items selected"))
            return
        }

        exitSelectionMode()
        _events.tryEmit(AlbumDetailEvent.Message("Removing from album..."))

        viewModelScope.launch {
            val result = albumRepository.removeMediaFromAlbumBulk(albumId, selectedSnapshot)
            val message = buildRemoveSummaryMessage(result)
            _events.tryEmit(AlbumDetailEvent.Message(message))
        }
    }

    private suspend fun refreshSelectableCount(): Int {
        // For albums, we estimate based on current selection state
        // In a real implementation, you might want to query the actual count
        val current = _selectionState.value
        val count = current.selectableCount.coerceAtLeast(current.selectedCount)
        _selectionState.update { it.copy(selectableCount = count) }
        return count
    }

    private fun buildRemoveSummaryMessage(result: RemoveMediaBulkResult): String {
        return when {
            result.removedCount > 0 && result.notInAlbumCount > 0 ->
                "Removed ${result.removedCount}, ${result.notInAlbumCount} not in album"
            result.removedCount > 0 ->
                "Removed ${result.removedCount} items"
            result.notInAlbumCount > 0 ->
                "Items not in album"
            else -> "Nothing to remove"
        }
    }

    private companion object {
        const val SELECTION_CAP = 500
    }
}

class AlbumDetailViewModelFactory(
    private val albumId: Long,
    private val albumRepository: AlbumRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AlbumDetailViewModel(
            albumId = albumId,
            albumRepository = albumRepository,
        ) as T
    }
}

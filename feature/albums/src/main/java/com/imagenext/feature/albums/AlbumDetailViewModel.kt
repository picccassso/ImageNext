package com.imagenext.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.AlbumWriteResult
import com.imagenext.core.data.RemoveMediaResult
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

package com.imagenext.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.AlbumWriteResult
import com.imagenext.core.model.Album
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlbumsViewModel(
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumsUiState>(AlbumsUiState.Loading)
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        observeAlbums()
    }

    private fun observeAlbums() {
        viewModelScope.launch {
            albumRepository.observeAlbums().collect { albums ->
                _uiState.value = if (albums.isEmpty()) {
                    AlbumsUiState.Empty
                } else {
                    AlbumsUiState.Content(albums = albums)
                }
            }
        }
    }

    fun createAlbum(name: String) {
        viewModelScope.launch {
            when (val result = albumRepository.createAlbum(name)) {
                is AlbumWriteResult.Success -> _messages.tryEmit("Album created")
                is AlbumWriteResult.EmptyName -> _messages.tryEmit("Album name cannot be empty")
                is AlbumWriteResult.DuplicateName -> _messages.tryEmit("Album name already exists")
                is AlbumWriteResult.NotFound -> _messages.tryEmit("Album not found")
            }
        }
    }

    fun renameAlbum(albumId: Long, name: String) {
        viewModelScope.launch {
            when (val result = albumRepository.renameAlbum(albumId, name)) {
                is AlbumWriteResult.Success -> _messages.tryEmit("Album renamed")
                is AlbumWriteResult.EmptyName -> _messages.tryEmit("Album name cannot be empty")
                is AlbumWriteResult.DuplicateName -> _messages.tryEmit("Album name already exists")
                is AlbumWriteResult.NotFound -> _messages.tryEmit("Album not found")
            }
        }
    }

    fun deleteAlbum(albumId: Long) {
        viewModelScope.launch {
            albumRepository.deleteAlbum(albumId)
            _messages.tryEmit("Album deleted")
        }
    }
}

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object Empty : AlbumsUiState
    data class Content(val albums: List<Album>) : AlbumsUiState
}

class AlbumsViewModelFactory(
    private val albumRepository: AlbumRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AlbumsViewModel(
            albumRepository = albumRepository,
        ) as T
    }
}

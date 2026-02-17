package com.imagenext.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.MediaDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.imagenext.core.model.Album

/**
 * Albums state orchestration.
 *
 * Observes selected folders and their media items to build
 * album groupings. Each album maps to a selected folder with
 * a cover image and media count.
 */
class AlbumsViewModel(
    private val folderDao: FolderDao,
    private val mediaDao: MediaDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumsUiState>(AlbumsUiState.Loading)
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        observeAlbums()
    }

    private fun observeAlbums() {
        viewModelScope.launch {
            combine(
                folderDao.getSelectedFolders(),
                mediaDao.getAllMedia(),
            ) { folders, allMedia ->
                if (folders.isEmpty()) {
                    AlbumsUiState.Empty
                } else {
                    val mediaByFolder = allMedia.groupBy { it.folderPath }
                    val albums = folders.map { folder ->
                        val folderMedia = mediaByFolder[folder.remotePath].orEmpty()
                        val coverItem = folderMedia.firstOrNull()
                        Album(
                            folderPath = folder.remotePath,
                            displayName = folder.displayName,
                            mediaCount = folderMedia.size,
                            coverThumbnailPath = coverItem?.thumbnailPath,
                            coverRemotePath = coverItem?.remotePath,
                        )
                    }
                    AlbumsUiState.Content(albums = albums)
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}

/**
 * Represents a folder-based album for display.
 *
 * @property folderPath Remote path of the folder this album represents.
 * @property displayName Human-readable folder name.
 * @property mediaCount Number of media items in this album.
 * @property coverThumbnailPath Local path to the cover thumbnail, if available.
 * @property coverRemotePath Remote path of the cover media item, for fallback loading.
 */

/** UI state for the Albums tab. */
sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState
    data object Empty : AlbumsUiState
    data class Content(val albums: List<Album>) : AlbumsUiState
}

/**
 * Factory for [AlbumsViewModel].
 */
class AlbumsViewModelFactory(
    private val folderDao: FolderDao,
    private val mediaDao: MediaDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AlbumsViewModel(
            folderDao = folderDao,
            mediaDao = mediaDao,
        ) as T
    }
}

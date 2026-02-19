package com.imagenext.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.ViewerRepository
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Viewer state and interaction orchestration.
 *
 * Loads the full ordered media list, resolves the initial item by
 * remote path, and manages the current viewing position.
 */
class ViewerViewModel(
    private val viewerRepository: ViewerRepository,
    private val initialRemotePath: String,
    private val albumId: Long? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        loadViewer()
    }

    private fun loadViewer() {
        viewModelScope.launch {
            val allItems = viewerRepository.getMediaOrdered(albumId = albumId)
            if (allItems.isEmpty()) {
                _uiState.value = ViewerUiState.Error("No media items available.")
                return@launch
            }

            val startIndex = allItems.indexOfFirst { it.remotePath == initialRemotePath }
            if (startIndex == -1) {
                _uiState.value = ViewerUiState.Error("Media not found.")
                return@launch
            }

            _uiState.value = buildContentState(
                items = allItems,
                index = startIndex,
            )
        }
    }

    /** Updates the current page index when the user swipes. */
    fun onPageChanged(index: Int) {
        val current = _uiState.value
        if (current is ViewerUiState.Content && index in current.items.indices) {
            _uiState.value = buildContentState(
                items = current.items,
                index = index,
                showMetadata = current.showMetadata,
            )
        }
    }

    /** Shows the metadata overlay. */
    fun showMetadata() {
        val current = _uiState.value
        if (current is ViewerUiState.Content) {
            _uiState.value = current.copy(showMetadata = true)
        }
    }

    /** Hides the metadata overlay. */
    fun hideMetadata() {
        val current = _uiState.value
        if (current is ViewerUiState.Content) {
            _uiState.value = current.copy(showMetadata = false)
        }
    }

    private fun buildContentState(
        items: List<MediaItem>,
        index: Int,
        showMetadata: Boolean = false,
    ): ViewerUiState.Content {
        val currentItem = items[index]
        val currentSource = viewerRepository
            .getRemoteMediaSource(remotePath = currentItem.remotePath, fileId = currentItem.fileId)
            ?.toUiSource()
        val prefetchSources = buildAdjacentImageSources(items = items, centerIndex = index)

        return ViewerUiState.Content(
            items = items,
            currentIndex = index,
            currentMediaSource = currentSource,
            prefetchImageSources = prefetchSources,
            showMetadata = showMetadata,
        )
    }

    private fun buildAdjacentImageSources(
        items: List<MediaItem>,
        centerIndex: Int,
    ): List<ViewerMediaSource> {
        if (items.isEmpty() || centerIndex !in items.indices) return emptyList()

        val start = (centerIndex - PREFETCH_WINDOW).coerceAtLeast(0)
        val end = (centerIndex + PREFETCH_WINDOW).coerceAtMost(items.lastIndex)

        return (start..end)
            .asSequence()
            .filter { it != centerIndex }
            .map { index -> items[index] }
            .filter { item -> item.isImage }
            .mapNotNull { item ->
                viewerRepository.getRemoteMediaSource(
                    remotePath = item.remotePath,
                    fileId = item.fileId,
                )
            }
            .map { source -> source.toUiSource() }
            .toList()
    }

    private fun ViewerRepository.RemoteMediaSource.toUiSource(): ViewerMediaSource {
        return ViewerMediaSource(
            remotePath = remotePath,
            fullResUrl = fullResUrl,
            previewUrl = previewUrl,
            authHeader = authHeader,
        )
    }

    private companion object {
        const val PREFETCH_WINDOW = 1
    }
}

/**
 * Factory for [ViewerViewModel].
 */
class ViewerViewModelFactory(
    private val viewerRepository: ViewerRepository,
    private val initialRemotePath: String,
    private val albumId: Long? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ViewerViewModel(
            viewerRepository = viewerRepository,
            initialRemotePath = initialRemotePath,
            albumId = albumId,
        ) as T
    }
}

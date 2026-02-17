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
) : ViewModel() {

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        loadViewer()
    }

    private fun loadViewer() {
        viewModelScope.launch {
            val allItems = viewerRepository.getAllMediaOrdered()
            if (allItems.isEmpty()) {
                _uiState.value = ViewerUiState.Error("No media items available.")
                return@launch
            }

            val startIndex = allItems.indexOfFirst { it.remotePath == initialRemotePath }
            if (startIndex == -1) {
                _uiState.value = ViewerUiState.Error("Image not found.")
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

    /** Toggles the metadata overlay visibility. */
    fun toggleMetadata() {
        val current = _uiState.value
        if (current is ViewerUiState.Content) {
            _uiState.value = current.copy(showMetadata = !current.showMetadata)
        }
    }

    private fun buildContentState(
        items: List<MediaItem>,
        index: Int,
        showMetadata: Boolean = false,
    ): ViewerUiState.Content {
        val currentItem = items[index]
        val currentSource = viewerRepository.getRemoteImageSource(currentItem.remotePath)?.toUiSource()
        val prefetchSources = viewerRepository.getAdjacentRemoteImageSources(
            items = items,
            centerIndex = index,
            window = PREFETCH_WINDOW,
        ).map { it.toUiSource() }

        return ViewerUiState.Content(
            items = items,
            currentIndex = index,
            currentImageSource = currentSource,
            prefetchSources = prefetchSources,
            showMetadata = showMetadata,
        )
    }

    private fun ViewerRepository.RemoteImageSource.toUiSource(): ViewerImageSource {
        return ViewerImageSource(
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ViewerViewModel(
            viewerRepository = viewerRepository,
            initialRemotePath = initialRemotePath,
        ) as T
    }
}

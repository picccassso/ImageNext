package com.imagenext.feature.viewer

import com.imagenext.core.model.MediaItem

data class ViewerMediaSource(
    val remotePath: String,
    val fullResUrl: String,
    val previewUrl: String,
    val authHeader: String,
)

/** UI state for the fullscreen viewer. */
sealed interface ViewerUiState {
    /** Initial loading state. */
    data object Loading : ViewerUiState

    /** Error state when the requested item cannot be found. */
    data class Error(val message: String) : ViewerUiState

    /** Content state with the ordered list and current index. */
    data class Content(
        /** All media items in timeline order. */
        val items: List<MediaItem>,
        /** Index of the currently displayed item. */
        val currentIndex: Int,
        /** Authenticated source for the current page media. */
        val currentMediaSource: ViewerMediaSource? = null,
        /** Authenticated sources to prefetch for adjacent images. */
        val prefetchImageSources: List<ViewerMediaSource> = emptyList(),
        /** Whether the metadata overlay is visible. */
        val showMetadata: Boolean = false,
    ) : ViewerUiState {
        val currentItem: MediaItem get() = items[currentIndex]
        val hasPrevious: Boolean get() = currentIndex > 0
        val hasNext: Boolean get() = currentIndex < items.size - 1
    }
}

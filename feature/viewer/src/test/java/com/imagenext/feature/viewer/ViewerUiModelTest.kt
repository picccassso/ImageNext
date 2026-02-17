package com.imagenext.feature.viewer

import com.imagenext.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerUiModelTest {

    private fun mediaItem(remotePath: String) = MediaItem(
        remotePath = remotePath,
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        size = 1024,
        lastModified = 1000L,
        etag = "etag",
        folderPath = "/photos",
    )

    @Test
    fun `content state currentItem returns item at currentIndex`() {
        val items = listOf(mediaItem("/a.jpg"), mediaItem("/b.jpg"), mediaItem("/c.jpg"))
        val state = ViewerUiState.Content(items = items, currentIndex = 1)

        assertEquals("/b.jpg", state.currentItem.remotePath)
    }

    @Test
    fun `hasPrevious is false at index 0`() {
        val items = listOf(mediaItem("/a.jpg"), mediaItem("/b.jpg"))
        val state = ViewerUiState.Content(items = items, currentIndex = 0)

        assertFalse(state.hasPrevious)
        assertTrue(state.hasNext)
    }

    @Test
    fun `hasNext is false at last index`() {
        val items = listOf(mediaItem("/a.jpg"), mediaItem("/b.jpg"))
        val state = ViewerUiState.Content(items = items, currentIndex = 1)

        assertTrue(state.hasPrevious)
        assertFalse(state.hasNext)
    }

    @Test
    fun `single item has neither previous nor next`() {
        val items = listOf(mediaItem("/a.jpg"))
        val state = ViewerUiState.Content(items = items, currentIndex = 0)

        assertFalse(state.hasPrevious)
        assertFalse(state.hasNext)
    }

    @Test
    fun `showMetadata defaults to false`() {
        val state = ViewerUiState.Content(
            items = listOf(mediaItem("/a.jpg")),
            currentIndex = 0,
        )

        assertFalse(state.showMetadata)
    }
}

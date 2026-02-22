package com.imagenext.feature.photos

import com.imagenext.core.data.AddMediaBulkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosSelectionLogicTest {

    @Test
    fun `long press enters selection mode with one selected`() {
        val initial = PhotosSelectionState(maxSelectableCount = 500)

        val mutation = selectFromLongPress(
            state = initial,
            remotePath = "/a.jpg",
            selectableCount = 120,
            maxSelectableCount = 500,
        )

        val updated = mutation as SelectionMutation.Updated
        assertEquals(true, updated.state.isSelectionMode)
        assertEquals(setOf("/a.jpg"), updated.state.selectedRemotePaths)
        assertEquals(120, updated.state.selectableCount)
    }

    @Test
    fun `tap toggle deselects last item and exits selection mode`() {
        val current = PhotosSelectionState(
            isSelectionMode = true,
            selectedRemotePaths = setOf("/a.jpg"),
            selectableCount = 30,
            maxSelectableCount = 500,
        )

        val mutation = toggleFromTap(
            state = current,
            remotePath = "/a.jpg",
            selectableCount = 30,
            maxSelectableCount = 500,
        )

        val updated = mutation as SelectionMutation.Updated
        assertEquals(false, updated.state.isSelectionMode)
        assertTrue(updated.state.selectedRemotePaths.isEmpty())
        assertEquals(0, updated.state.selectedCount)
    }

    @Test
    fun `tap toggle is capped at max selection`() {
        val current = PhotosSelectionState(
            isSelectionMode = true,
            selectedRemotePaths = (1..500).map { "/$it.jpg" }.toSet(),
            selectableCount = 500,
            maxSelectableCount = 500,
        )

        val mutation = toggleFromTap(
            state = current,
            remotePath = "/501.jpg",
            selectableCount = 500,
            maxSelectableCount = 500,
        )

        assertTrue(mutation is SelectionMutation.CapExceeded)
    }

    @Test
    fun `select all applies newest paths capped to 500`() {
        val newestFirst = (1..600).map { "/$it.jpg" }

        val state = applySelectAllState(
            remotePaths = newestFirst,
            selectableCount = 500,
            maxSelectableCount = 500,
        )

        assertEquals(true, state.isSelectionMode)
        assertEquals(500, state.selectedCount)
        assertEquals(true, state.selectedRemotePaths.contains("/1.jpg"))
        assertEquals(true, state.selectedRemotePaths.contains("/500.jpg"))
        assertEquals(false, state.selectedRemotePaths.contains("/600.jpg"))
    }

    @Test
    fun `select all toggle should clear when all selectable already selected`() {
        val state = PhotosSelectionState(
            isSelectionMode = true,
            selectedRemotePaths = (1..500).map { "/$it.jpg" }.toSet(),
            selectableCount = 500,
            maxSelectableCount = 500,
        )

        assertEquals(true, shouldClearAllFromToggle(state, selectableCount = 500))
    }

    @Test
    fun `summary shows top two albums and remainder count`() {
        val rows = listOf(
            AlbumApplySummaryRow(
                albumName = "Trips",
                result = AddMediaBulkResult(addedCount = 4, alreadyInAlbumCount = 1, mediaNotFoundCount = 0, albumNotFound = false),
            ),
            AlbumApplySummaryRow(
                albumName = "Family",
                result = AddMediaBulkResult(addedCount = 2, alreadyInAlbumCount = 0, mediaNotFoundCount = 1, albumNotFound = false),
            ),
            AlbumApplySummaryRow(
                albumName = "Work",
                result = AddMediaBulkResult(addedCount = 1, alreadyInAlbumCount = 3, mediaNotFoundCount = 0, albumNotFound = false),
            ),
        )

        val message = buildAlbumApplySummaryMessage(rows, visibleLimit = 2)

        assertTrue(message.contains("Trips (added 4, already 1, missing 0)"))
        assertTrue(message.contains("Family (added 2, already 0, missing 1)"))
        assertTrue(message.contains("+1 more albums"))
    }
}

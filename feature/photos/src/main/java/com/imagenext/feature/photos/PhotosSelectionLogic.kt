package com.imagenext.feature.photos

import com.imagenext.core.data.AddMediaBulkResult

internal sealed interface SelectionMutation {
    data class Updated(val state: PhotosSelectionState) : SelectionMutation
    data object CapExceeded : SelectionMutation
    data object NoChange : SelectionMutation
}

internal fun selectFromLongPress(
    state: PhotosSelectionState,
    remotePath: String,
    selectableCount: Int,
    maxSelectableCount: Int,
): SelectionMutation {
    if (state.selectedRemotePaths.contains(remotePath)) {
        return SelectionMutation.NoChange
    }
    if (state.selectedCount >= maxSelectableCount) {
        return SelectionMutation.CapExceeded
    }
    return SelectionMutation.Updated(
        state.copy(
            isSelectionMode = true,
            selectedRemotePaths = state.selectedRemotePaths + remotePath,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        ),
    )
}

internal fun toggleFromTap(
    state: PhotosSelectionState,
    remotePath: String,
    selectableCount: Int,
    maxSelectableCount: Int,
): SelectionMutation {
    if (state.selectedRemotePaths.contains(remotePath)) {
        val reduced = state.selectedRemotePaths - remotePath
        val nextState = if (reduced.isEmpty()) {
            PhotosSelectionState(maxSelectableCount = maxSelectableCount)
        } else {
            state.copy(
                isSelectionMode = true,
                selectedRemotePaths = reduced,
                selectableCount = selectableCount,
                maxSelectableCount = maxSelectableCount,
            )
        }
        return SelectionMutation.Updated(nextState)
    }

    if (state.selectedCount >= maxSelectableCount) {
        return SelectionMutation.CapExceeded
    }

    return SelectionMutation.Updated(
        state.copy(
            isSelectionMode = true,
            selectedRemotePaths = state.selectedRemotePaths + remotePath,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        ),
    )
}

internal fun shouldClearAllFromToggle(
    state: PhotosSelectionState,
    selectableCount: Int,
): Boolean {
    return selectableCount > 0 && state.selectedCount == selectableCount
}

internal fun applySelectAllState(
    remotePaths: List<String>,
    selectableCount: Int,
    maxSelectableCount: Int,
): PhotosSelectionState {
    val selected = remotePaths.take(maxSelectableCount).toSet()
    return if (selected.isEmpty()) {
        PhotosSelectionState(maxSelectableCount = maxSelectableCount)
    } else {
        PhotosSelectionState(
            isSelectionMode = true,
            selectedRemotePaths = selected,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        )
    }
}

internal data class AlbumApplySummaryRow(
    val albumName: String,
    val result: AddMediaBulkResult,
)

internal fun buildAlbumApplySummaryMessage(
    rows: List<AlbumApplySummaryRow>,
    visibleLimit: Int = 2,
): String {
    if (rows.isEmpty()) return "No albums selected"

    val visible = rows.take(visibleLimit)
    val fragments = visible.map { row ->
        if (row.result.albumNotFound) {
            "${row.albumName} (album not found)"
        } else {
            "${row.albumName} (added ${row.result.addedCount}, already ${row.result.alreadyInAlbumCount}, missing ${row.result.mediaNotFoundCount})"
        }
    }

    val remainder = rows.size - visible.size
    val remainderSuffix = if (remainder > 0) "; +$remainder more albums" else ""
    return "Album update complete: ${fragments.joinToString(separator = "; ")}$remainderSuffix"
}

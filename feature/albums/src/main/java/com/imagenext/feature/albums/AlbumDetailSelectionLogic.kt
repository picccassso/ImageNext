package com.imagenext.feature.albums

internal sealed interface AlbumSelectionMutation {
    data class Updated(val state: AlbumDetailSelectionState) : AlbumSelectionMutation
    data object CapExceeded : AlbumSelectionMutation
    data object NoChange : AlbumSelectionMutation
}

internal fun selectFromLongPress(
    state: AlbumDetailSelectionState,
    remotePath: String,
    selectableCount: Int,
    maxSelectableCount: Int,
): AlbumSelectionMutation {
    if (state.selectedRemotePaths.contains(remotePath)) {
        return AlbumSelectionMutation.NoChange
    }
    if (state.selectedCount >= maxSelectableCount) {
        return AlbumSelectionMutation.CapExceeded
    }
    return AlbumSelectionMutation.Updated(
        state.copy(
            isSelectionMode = true,
            selectedRemotePaths = state.selectedRemotePaths + remotePath,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        ),
    )
}

internal fun toggleFromTap(
    state: AlbumDetailSelectionState,
    remotePath: String,
    selectableCount: Int,
    maxSelectableCount: Int,
): AlbumSelectionMutation {
    if (state.selectedRemotePaths.contains(remotePath)) {
        val reduced = state.selectedRemotePaths - remotePath
        val nextState = if (reduced.isEmpty()) {
            AlbumDetailSelectionState(maxSelectableCount = maxSelectableCount)
        } else {
            state.copy(
                isSelectionMode = true,
                selectedRemotePaths = reduced,
                selectableCount = selectableCount,
                maxSelectableCount = maxSelectableCount,
            )
        }
        return AlbumSelectionMutation.Updated(nextState)
    }

    if (state.selectedCount >= maxSelectableCount) {
        return AlbumSelectionMutation.CapExceeded
    }

    return AlbumSelectionMutation.Updated(
        state.copy(
            isSelectionMode = true,
            selectedRemotePaths = state.selectedRemotePaths + remotePath,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        ),
    )
}

internal fun shouldClearAllFromToggle(
    state: AlbumDetailSelectionState,
    selectableCount: Int,
): Boolean {
    return selectableCount > 0 && state.selectedCount == selectableCount
}

internal fun applySelectAllState(
    remotePaths: List<String>,
    selectableCount: Int,
    maxSelectableCount: Int,
): AlbumDetailSelectionState {
    val selected = remotePaths.take(maxSelectableCount).toSet()
    return if (selected.isEmpty()) {
        AlbumDetailSelectionState(maxSelectableCount = maxSelectableCount)
    } else {
        AlbumDetailSelectionState(
            isSelectionMode = true,
            selectedRemotePaths = selected,
            selectableCount = selectableCount,
            maxSelectableCount = maxSelectableCount,
        )
    }
}

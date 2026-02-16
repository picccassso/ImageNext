package com.imagenext.feature.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.model.AuthSession
import com.imagenext.core.model.SelectedFolder
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Folder selection state and action orchestration.
 *
 * Manages the complete folder selection flow:
 * 1. Discovers available folders from the Nextcloud server.
 * 2. Allows user to search, select, and deselect folders.
 * 3. Persists selections and triggers background sync on confirmation.
 */
class FolderSelectionViewModel(
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val session: AuthSession,
) : ViewModel() {

    /** Current UI state. */
    private val _uiState = MutableStateFlow<FolderSelectionUiState>(FolderSelectionUiState.Loading)
    val uiState: StateFlow<FolderSelectionUiState> = _uiState.asStateFlow()

    /** Current search query. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Currently selected folder paths. */
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    /** Sync state from orchestrator. */
    val syncState: StateFlow<SyncState> = syncOrchestrator.syncState

    /** All discovered folders (unfiltered). */
    private var allFolders: List<SelectedFolder> = emptyList()

    init {
        loadFolders()
    }

    /** Discovers available folders from the server. */
    fun loadFolders() {
        _uiState.value = FolderSelectionUiState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            val result = folderRepository.discoverFolders(
                serverUrl = session.serverUrl,
                loginName = session.loginName,
                appPassword = session.appPassword,
            )

            when (result) {
                is com.imagenext.core.network.webdav.WebDavClient.WebDavResult.Success -> {
                    allFolders = result.data
                    _uiState.value = FolderSelectionUiState.Ready(
                        folders = applySearchFilter(result.data),
                    )
                }
                is com.imagenext.core.network.webdav.WebDavClient.WebDavResult.Error -> {
                    _uiState.value = FolderSelectionUiState.Error(result.message)
                }
            }
        }
    }

    /** Updates the search query and filters the folder list. */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        val currentState = _uiState.value
        if (currentState is FolderSelectionUiState.Ready) {
            _uiState.value = currentState.copy(
                folders = applySearchFilter(allFolders),
            )
        }
    }

    /** Toggles selection of a folder. */
    fun onFolderToggled(folder: SelectedFolder) {
        val current = _selectedPaths.value.toMutableSet()
        if (current.contains(folder.remotePath)) {
            current.remove(folder.remotePath)
        } else {
            current.add(folder.remotePath)
        }
        _selectedPaths.value = current
    }

    /**
     * Confirms folder selection — persists selections and triggers sync.
     * Calls [onComplete] when finished.
     */
    fun onConfirmSelection(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val selected = _selectedPaths.value

            // Persist each selected folder
            for (folder in allFolders) {
                if (selected.contains(folder.remotePath)) {
                    folderRepository.addFolder(folder)
                }
            }

            // Trigger background sync
            syncOrchestrator.scheduleSyncForFolders()

            // Navigate to main app
            launch(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun applySearchFilter(folders: List<SelectedFolder>): List<SelectedFolder> {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return folders
        return folders.filter { folder ->
            folder.displayName.contains(query, ignoreCase = true) ||
                folder.remotePath.contains(query, ignoreCase = true)
        }
    }
}

/** UI state for the folder selection screen. */
sealed interface FolderSelectionUiState {
    /** Loading folders from server. */
    data object Loading : FolderSelectionUiState

    /** Folders loaded and ready for selection. */
    data class Ready(
        val folders: List<SelectedFolder>,
    ) : FolderSelectionUiState

    /** An error occurred during folder discovery. */
    data class Error(val message: String) : FolderSelectionUiState
}

/**
 * Factory for [FolderSelectionViewModel] to support creation through
 * `viewModel(factory = ...)` at the folder-selection route.
 */
class FolderSelectionViewModelFactory(
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val session: AuthSession,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FolderSelectionViewModel(
            folderRepository = folderRepository,
            syncOrchestrator = syncOrchestrator,
            session = session,
        ) as T
    }
}

package com.imagenext.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.model.SyncState
import com.imagenext.core.security.AppLockManager
import com.imagenext.core.security.CertificateTrustStore
import com.imagenext.core.security.SessionRepository
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings state and action orchestration.
 *
 * Provides account info, sync status, security controls, and logout functionality.
 * All credential data is read through [SessionRepository] with no passwords exposed to UI.
 */
class SettingsViewModel(
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val certificateTrustStore: CertificateTrustStore,
    private val appLockManager: AppLockManager,
    private val database: AppDatabase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _logoutEvent = MutableStateFlow(false)
    val logoutEvent: StateFlow<Boolean> = _logoutEvent.asStateFlow()

    init {
        loadSettings()
        observeSyncState()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val session = sessionRepository.getSession()
            val folderCount = folderRepository.getSelectedCount()
            val trustedCerts = certificateTrustStore.getAll()
            val isLockEnabled = appLockManager.isLockEnabled()

            _uiState.value = _uiState.value.copy(
                serverUrl = session?.serverUrl ?: "",
                loginName = session?.loginName ?: "",
                selectedFolderCount = folderCount,
                trustedCertificates = trustedCerts,
                isAppLockEnabled = isLockEnabled,
                isLoading = false,
            )
        }
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            syncOrchestrator.observeSyncState().collect { syncState ->
                _uiState.value = _uiState.value.copy(syncState = syncState)
            }
        }
    }

    /** Retries sync by re-scheduling the full workflow. */
    fun retrySync() {
        viewModelScope.launch {
            syncOrchestrator.retrySync()
        }
    }

    /** Revokes trust for a certificate fingerprint. */
    fun revokeCertificate(fingerprint: String) {
        certificateTrustStore.revoke(fingerprint)
        _uiState.value = _uiState.value.copy(
            trustedCertificates = certificateTrustStore.getAll(),
        )
    }

    /** Toggles app lock on or off. */
    fun setAppLockEnabled(enabled: Boolean) {
        appLockManager.setLockEnabled(enabled)
        _uiState.value = _uiState.value.copy(isAppLockEnabled = enabled)
    }

    /**
     * Performs secure logout sequence.
     *
     * 1. Cancels active sync work.
     * 2. Clears session credentials from encrypted vault.
     * 3. Clears certificate trust decisions.
     * 4. Resets app lock state.
     * 5. Clears local database (media metadata, folders, checkpoints).
     * 6. Signals navigation to return to onboarding.
     */
    fun logout() {
        viewModelScope.launch {
            // Cancel active sync
            syncOrchestrator.cancelSync()

            // Clear all session secrets
            sessionRepository.clearSession()

            // Clear certificate trust
            certificateTrustStore.clearAll()

            // Reset app lock
            appLockManager.reset()

            // Clear local database
            database.clearAllTables()

            // Signal logout event for navigation
            _logoutEvent.value = true
        }
    }

    /** Resets the logout event after navigation has been handled. */
    fun onLogoutHandled() {
        _logoutEvent.value = false
    }
}

/**
 * UI state for the Settings screen.
 *
 * Credential fields are intentionally limited to display-safe info
 * (server URL and login name — never passwords).
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val serverUrl: String = "",
    val loginName: String = "",
    val selectedFolderCount: Int = 0,
    val syncState: SyncState = SyncState.Idle,
    val trustedCertificates: List<CertificateTrustStore.TrustedCertificate> = emptyList(),
    val isAppLockEnabled: Boolean = false,
)

/**
 * Factory for [SettingsViewModel].
 */
class SettingsViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val certificateTrustStore: CertificateTrustStore,
    private val appLockManager: AppLockManager,
    private val database: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(
            sessionRepository = sessionRepository,
            folderRepository = folderRepository,
            syncOrchestrator = syncOrchestrator,
            certificateTrustStore = certificateTrustStore,
            appLockManager = appLockManager,
            database = database,
        ) as T
    }
}

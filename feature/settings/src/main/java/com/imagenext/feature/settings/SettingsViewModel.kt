package com.imagenext.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.model.SyncState
import com.imagenext.core.network.auth.NextcloudAuthApi
import com.imagenext.core.security.AppLockManager
import com.imagenext.core.security.CertificateTrustStore
import com.imagenext.core.security.LockMethod
import com.imagenext.core.security.SessionRepository
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings state and action orchestration.
 *
 * Provides account info, sync status, security controls, and logout functionality.
 * All credential data is read through [SessionRepository] with no passwords exposed to UI.
 */
class SettingsViewModel(
    private val sessionRepository: SessionRepository,
    private val authApi: NextcloudAuthApi,
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val certificateTrustStore: CertificateTrustStore,
    private val appLockManager: AppLockManager,
    private val database: AppDatabase,
) : ViewModel() {

    private val folderDao by lazy { database.folderDao() }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _logoutEvent = MutableStateFlow(false)
    val logoutEvent: StateFlow<Boolean> = _logoutEvent.asStateFlow()

    init {
        loadSettings()
        observeSyncState()
        observeConnectionStatus()
    }

    private fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.getSession()
                val folderCount = folderRepository.getSelectedCount()
                val trustedCerts = certificateTrustStore.getAll()
                val isLockEnabled = appLockManager.isLockEnabled()
                val lockMethod = appLockManager.getLockMethod()
                val biometricAvailable = appLockManager.isBiometricAvailable()
                val hasPin = appLockManager.hasPin()
                val connectionStatus = when {
                    session == null -> ConnectionStatus.NOT_CONNECTED
                    else -> ConnectionStatus.NOT_CONNECTED
                }

                _uiState.value = _uiState.value.copy(
                    serverUrl = session?.serverUrl ?: "",
                    loginName = session?.loginName ?: "",
                    connectionStatus = connectionStatus,
                    selectedFolderCount = folderCount,
                    trustedCertificates = trustedCerts,
                    isAppLockEnabled = isLockEnabled,
                    lockMethod = lockMethod,
                    isBiometricAvailable = biometricAvailable,
                    hasPin = hasPin,
                    isLoading = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun observeSyncState() {
        viewModelScope.launch {
            syncOrchestrator.observeSyncState().collect { syncState ->
                val syncIssue = when (syncState) {
                    SyncState.Failed,
                    SyncState.Partial,
                    -> resolveLatestSyncIssue()
                    else -> null
                }
                _uiState.value = _uiState.value.copy(
                    syncState = syncState,
                    syncIssue = syncIssue,
                )
            }
        }
    }

    private suspend fun resolveLatestSyncIssue(): String? {
        val checkpoint = withContext(Dispatchers.IO) {
            folderDao.getLatestCheckpointWithError()
        } ?: return null

        val errorCode = checkpoint.lastErrorCode.orEmpty()
        val rawMessage = checkpoint.lastErrorMessage?.trim()
        val httpCode = HTTP_CODE_SUFFIX_REGEX.find(errorCode)?.groupValues?.get(1)

        val baseMessage = when {
            errorCode.startsWith(ERROR_PREFIX_AUTH) -> "Authentication failed for selected folders"
            errorCode.startsWith(ERROR_PREFIX_NOT_FOUND) -> "A selected folder was not found on server"
            errorCode.startsWith(ERROR_PREFIX_SECURITY) -> "Secure connection validation failed"
            errorCode.startsWith(ERROR_PREFIX_TRANSIENT) -> "Temporary network or server error"
            errorCode.startsWith(ERROR_PREFIX_CLIENT) -> "Server rejected the folder request"
            !rawMessage.isNullOrBlank() -> rawMessage
            else -> "Sync failed for selected folders"
        }

        return if (httpCode != null && !baseMessage.contains("HTTP")) {
            "$baseMessage (HTTP $httpCode)"
        } else {
            baseMessage
        }
    }

    /**
     * Periodically refreshes live server connectivity so Settings reflects
     * connection changes without requiring an app restart.
     */
    private fun observeConnectionStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val nextStatus = try {
                    val session = sessionRepository.getSession()
                    when {
                        session == null -> ConnectionStatus.NOT_CONNECTED
                        authApi.checkServerReachability(session.serverUrl) is NextcloudAuthApi.AuthResult.Success ->
                            ConnectionStatus.CONNECTED
                        else -> ConnectionStatus.NOT_CONNECTED
                    }
                } catch (_: Exception) {
                    ConnectionStatus.NOT_CONNECTED
                }

                if (_uiState.value.connectionStatus != nextStatus) {
                    _uiState.value = _uiState.value.copy(connectionStatus = nextStatus)
                }

                delay(CONNECTION_STATUS_REFRESH_MS)
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
        if (enabled) {
            val method = appLockManager.getLockMethod()
            val canEnable = when (method) {
                LockMethod.PIN -> appLockManager.hasPin()
                LockMethod.BIOMETRIC -> appLockManager.isBiometricAvailable() && appLockManager.hasPin()
            }
            if (!canEnable) {
                _uiState.value = _uiState.value.copy(
                    isAppLockEnabled = false,
                    lockMethod = method,
                    hasPin = appLockManager.hasPin(),
                )
                return
            }
        }
        appLockManager.setLockEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            isAppLockEnabled = enabled,
            lockMethod = appLockManager.getLockMethod(),
            hasPin = appLockManager.hasPin(),
        )
    }

    /** Updates the selected app lock method. */
    fun setLockMethod(method: LockMethod) {
        if (method == LockMethod.BIOMETRIC && !appLockManager.hasPin()) {
            _uiState.value = _uiState.value.copy(
                lockMethod = LockMethod.PIN,
                hasPin = false,
            )
            return
        }
        appLockManager.setLockMethod(method)
        _uiState.value = _uiState.value.copy(lockMethod = method)
    }

    /** Stores a PIN for PIN-based unlock and ensures lock is enabled. */
    fun savePin(pin: String) {
        appLockManager.setPin(pin)
        appLockManager.setLockEnabled(true)
        _uiState.value = _uiState.value.copy(
            hasPin = true,
            isAppLockEnabled = true,
            lockMethod = LockMethod.PIN,
        )
    }

    /** Verifies whether the provided PIN matches the stored app lock PIN. */
    fun verifyPin(pin: String): Boolean = appLockManager.verifyPin(pin)

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
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val selectedFolderCount: Int = 0,
    val syncState: SyncState = SyncState.Idle,
    val syncIssue: String? = null,
    val trustedCertificates: List<CertificateTrustStore.TrustedCertificate> = emptyList(),
    val isAppLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.PIN,
    val isBiometricAvailable: Boolean = false,
    val hasPin: Boolean = false,
)

enum class ConnectionStatus {
    CONNECTED,
    NOT_CONNECTED,
}

private const val CONNECTION_STATUS_REFRESH_MS = 10_000L
private val HTTP_CODE_SUFFIX_REGEX = Regex("_http_(\\d+)$")
private const val ERROR_PREFIX_TRANSIENT = "transient"
private const val ERROR_PREFIX_AUTH = "auth"
private const val ERROR_PREFIX_NOT_FOUND = "not_found"
private const val ERROR_PREFIX_SECURITY = "security"
private const val ERROR_PREFIX_CLIENT = "client"

/**
 * Factory for [SettingsViewModel].
 */
class SettingsViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authApi: NextcloudAuthApi,
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
            authApi = authApi,
            folderRepository = folderRepository,
            syncOrchestrator = syncOrchestrator,
            certificateTrustStore = certificateTrustStore,
            appLockManager = appLockManager,
            database = database,
        ) as T
    }
}

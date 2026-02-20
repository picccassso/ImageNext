package com.imagenext.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.data.BackupPolicyRepository
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.data.LocalMediaDetector
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.LocalBackupFolderEntity
import com.imagenext.core.model.BackupPolicy
import com.imagenext.core.model.BackupSyncState
import com.imagenext.core.model.SyncState
import com.imagenext.core.model.SelectedFolder
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
 */
class SettingsViewModel(
    private val sessionRepository: SessionRepository,
    private val authApi: NextcloudAuthApi,
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val certificateTrustStore: CertificateTrustStore,
    private val appLockManager: AppLockManager,
    private val database: AppDatabase,
    private val backupPolicyRepository: BackupPolicyRepository,
    private val localMediaDetector: LocalMediaDetector,
) : ViewModel() {

    private val folderDao by lazy { database.folderDao() }
    private val localBackupFolderDao by lazy { database.localBackupFolderDao() }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _logoutEvent = MutableStateFlow(false)
    val logoutEvent: StateFlow<Boolean> = _logoutEvent.asStateFlow()
    private var hasValidatedBackupRoot = false

    init {
        loadSettings()
        observeSyncState()
        observeBackupState()
        observeBackupPolicy()
        observeSelectedBackupFolders()
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
                val hasMediaPermission = localMediaDetector.hasReadPermission()
                val backupPolicy = backupPolicyRepository.getPolicy()

                _uiState.value = _uiState.value.copy(
                    serverUrl = session?.serverUrl ?: "",
                    loginName = session?.loginName ?: "",
                    connectionStatus = ConnectionStatus.NOT_CONNECTED,
                    selectedFolderCount = folderCount,
                    trustedCertificates = trustedCerts,
                    isAppLockEnabled = isLockEnabled,
                    lockMethod = lockMethod,
                    isBiometricAvailable = biometricAvailable,
                    hasPin = hasPin,
                    hasMediaPermission = hasMediaPermission,
                    backupPolicy = backupPolicy,
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

    private fun observeBackupState() {
        viewModelScope.launch {
            syncOrchestrator.observeBackupState().collect { backupState ->
                _uiState.value = _uiState.value.copy(backupSyncState = backupState)
            }
        }
    }

    private fun observeBackupPolicy() {
        viewModelScope.launch {
            backupPolicyRepository.policyFlow.collect { policy ->
                val normalizedBackupRoot = normalizeRemotePath(policy.backupRoot)
                val hasKnownBackupRoot = _uiState.value.backupFolderOptions
                    .any { it.remotePath == normalizedBackupRoot }
                val needsReselection = if (!policy.backupRootSelectedByUser) {
                    true
                } else if (_uiState.value.backupFolderOptions.isNotEmpty()) {
                    !hasKnownBackupRoot
                } else {
                    _uiState.value.backupRootNeedsReselection
                }
                _uiState.value = _uiState.value.copy(
                    backupPolicy = policy.copy(backupRoot = normalizedBackupRoot),
                    hasMediaPermission = localMediaDetector.hasReadPermission(),
                    backupRootNeedsReselection = needsReselection,
                )
                syncOrchestrator.applyBackupScheduling(policy)
                if (
                    policy.enabled &&
                    policy.autoSelectBackupRoot &&
                    policy.backupRootSelectedByUser &&
                    !needsReselection
                ) {
                    ensureBackupRootSelected(normalizedBackupRoot)
                }
                if (policy.enabled || !policy.backupRootSelectedByUser) {
                    if (!hasValidatedBackupRoot) {
                        hasValidatedBackupRoot = true
                        refreshBackupFolderOptions()
                    }
                } else {
                    hasValidatedBackupRoot = false
                }
            }
        }
    }

    private fun observeSelectedBackupFolders() {
        viewModelScope.launch {
            localBackupFolderDao.observeAll().collect { rows ->
                _uiState.value = _uiState.value.copy(
                    selectedLocalFolders = rows.map { row ->
                        LocalBackupFolderOption(
                            treeUri = row.treeUri,
                            displayName = row.displayName,
                        )
                    }
                )
            }
        }
    }

    private suspend fun ensureBackupRootSelected(backupRoot: String) {
        val cleanRoot = normalizeRemotePath(backupRoot)
        val selected = folderRepository.getSelectedFoldersList()
        val alreadySelected = selected.any { normalizeRemotePath(it.remotePath) == cleanRoot }
        if (alreadySelected) return

        folderRepository.addFolder(
            SelectedFolder(
                remotePath = cleanRoot,
                displayName = cleanRoot.substringAfterLast('/').ifBlank { "ImageNext Backup" },
            )
        )
    }

    fun onMediaPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(hasMediaPermission = granted)
    }

    fun updateBackupPolicy(transform: (BackupPolicy) -> BackupPolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            backupPolicyRepository.update(transform)
        }
    }

    fun openBackupFolderPicker() {
        _uiState.value = _uiState.value.copy(
            isBackupFolderPickerVisible = true,
            backupFolderPickerError = null,
        )
        refreshBackupFolderOptions()
    }

    fun dismissBackupFolderPicker() {
        _uiState.value = _uiState.value.copy(isBackupFolderPickerVisible = false)
    }

    fun refreshBackupFolderOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isBackupFolderPickerLoading = true,
                backupFolderPickerError = null,
            )

            val session = sessionRepository.getSession()
            if (session == null) {
                _uiState.value = _uiState.value.copy(
                    isBackupFolderPickerLoading = false,
                    backupFolderPickerError = "Not connected. Please sign in again.",
                )
                return@launch
            }

            when (
                val result = folderRepository.discoverFolders(
                    serverUrl = session.serverUrl,
                    loginName = session.loginName,
                    appPassword = session.appPassword,
                )
            ) {
                is com.imagenext.core.network.webdav.WebDavClient.WebDavResult.Success -> {
                    val options = result.data
                        .map { folder ->
                            BackupRemoteFolderOption(
                                remotePath = normalizeRemotePath(folder.remotePath),
                                displayName = folder.displayName.ifBlank {
                                    normalizeRemotePath(folder.remotePath)
                                        .substringAfterLast('/')
                                        .ifBlank { "/" }
                                },
                            )
                        }
                        .distinctBy { it.remotePath }
                        .sortedBy { it.displayName.lowercase() }

                    val normalizedBackupRoot = normalizeRemotePath(_uiState.value.backupPolicy.backupRoot)
                    val hasMatch = options.any { it.remotePath == normalizedBackupRoot }
                    val rootSelectedByUser = _uiState.value.backupPolicy.backupRootSelectedByUser

                    _uiState.value = _uiState.value.copy(
                        backupFolderOptions = options,
                        isBackupFolderPickerLoading = false,
                        backupFolderPickerError = null,
                        backupRootNeedsReselection = !rootSelectedByUser || !hasMatch,
                    )
                }

                is com.imagenext.core.network.webdav.WebDavClient.WebDavResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isBackupFolderPickerLoading = false,
                        backupFolderPickerError = result.message,
                    )
                }
            }
        }
    }

    fun selectBackupRoot(folderRemotePath: String, @Suppress("UNUSED_PARAMETER") displayName: String) {
        val normalized = normalizeRemotePath(folderRemotePath)
        viewModelScope.launch(Dispatchers.IO) {
            backupPolicyRepository.update { current ->
                current.copy(
                    backupRoot = normalized,
                    backupRootSelectedByUser = true,
                )
            }
            _uiState.value = _uiState.value.copy(
                isBackupFolderPickerVisible = false,
                backupFolderPickerError = null,
                backupRootNeedsReselection = false,
            )
        }
    }

    fun addLocalBackupFolder(treeUri: String, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val finalDisplayName = displayName?.takeIf { it.isNotBlank() }
                ?: deriveDisplayNameFromTreeUri(treeUri)

            localBackupFolderDao.upsert(
                LocalBackupFolderEntity(
                    treeUri = treeUri,
                    displayName = finalDisplayName,
                    addedAt = now,
                )
            )
        }
    }

    fun removeLocalBackupFolder(treeUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            localBackupFolderDao.deleteByUri(treeUri)
        }
    }

    fun syncNowCombined() {
        viewModelScope.launch {
            syncOrchestrator.requestCombinedSyncNow()
        }
    }

    private fun deriveDisplayNameFromTreeUri(treeUri: String): String {
        val uri = Uri.parse(treeUri)
        val last = uri.lastPathSegment.orEmpty()
        if (last.isBlank()) return "Selected folder"
        return Uri.decode(last).substringAfterLast(':').ifBlank { "Selected folder" }
    }

    private fun normalizeRemotePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return "/"
        val withPrefix = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withPrefix.replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
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
     * Periodically refreshes live server connectivity so Settings reflects changes.
     */
    private fun observeConnectionStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val nextStatus = try {
                    val session = sessionRepository.getSession()
                    when {
                        session == null -> ConnectionStatus.NOT_CONNECTED
                        authApi.validateCredentials(
                            serverUrl = session.serverUrl,
                            loginName = session.loginName,
                            appPassword = session.appPassword,
                        ) is NextcloudAuthApi.AuthResult.Success ->
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

    fun retrySync() {
        viewModelScope.launch {
            syncOrchestrator.retrySync()
        }
    }

    fun revokeCertificate(fingerprint: String) {
        certificateTrustStore.revoke(fingerprint)
        _uiState.value = _uiState.value.copy(
            trustedCertificates = certificateTrustStore.getAll(),
        )
    }

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

    fun savePin(pin: String) {
        appLockManager.setPin(pin)
        appLockManager.setLockEnabled(true)
        _uiState.value = _uiState.value.copy(
            hasPin = true,
            isAppLockEnabled = true,
            lockMethod = LockMethod.PIN,
        )
    }

    fun verifyPin(pin: String): Boolean = appLockManager.verifyPin(pin)

    fun logout() {
        viewModelScope.launch {
            syncOrchestrator.cancelSync()
            sessionRepository.clearSession()
            certificateTrustStore.clearAll()
            appLockManager.reset()
            database.clearAllTables()
            _logoutEvent.value = true
        }
    }

    fun onLogoutHandled() {
        _logoutEvent.value = false
    }
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val serverUrl: String = "",
    val loginName: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val selectedFolderCount: Int = 0,
    val syncState: SyncState = SyncState.Idle,
    val syncIssue: String? = null,
    val backupSyncState: BackupSyncState = BackupSyncState(),
    val backupPolicy: BackupPolicy = BackupPolicy(),
    val hasMediaPermission: Boolean = false,
    val selectedLocalFolders: List<LocalBackupFolderOption> = emptyList(),
    val backupFolderOptions: List<BackupRemoteFolderOption> = emptyList(),
    val isBackupFolderPickerVisible: Boolean = false,
    val isBackupFolderPickerLoading: Boolean = false,
    val backupFolderPickerError: String? = null,
    val backupRootNeedsReselection: Boolean = false,
    val trustedCertificates: List<CertificateTrustStore.TrustedCertificate> = emptyList(),
    val isAppLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.PIN,
    val isBiometricAvailable: Boolean = false,
    val hasPin: Boolean = false,
)

data class LocalBackupFolderOption(
    val treeUri: String,
    val displayName: String,
)

data class BackupRemoteFolderOption(
    val remotePath: String,
    val displayName: String,
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

class SettingsViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authApi: NextcloudAuthApi,
    private val folderRepository: FolderRepositoryImpl,
    private val syncOrchestrator: SyncOrchestrator,
    private val certificateTrustStore: CertificateTrustStore,
    private val appLockManager: AppLockManager,
    private val database: AppDatabase,
    private val backupPolicyRepository: BackupPolicyRepository,
    private val localMediaDetector: LocalMediaDetector,
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
            backupPolicyRepository = backupPolicyRepository,
            localMediaDetector = localMediaDetector,
        ) as T
    }
}

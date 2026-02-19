package com.imagenext.feature.settings

import android.app.Activity
import android.os.Build
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagenext.core.model.BackupDeletePolicy
import com.imagenext.core.model.BackupNetworkPolicy
import com.imagenext.core.model.BackupPowerPolicy
import com.imagenext.core.model.BackupScheduleType
import com.imagenext.core.model.BackupSourceScope
import com.imagenext.core.model.BackupSyncMode
import com.imagenext.core.model.BackupUploadStructure
import com.imagenext.core.model.BackupRunState
import com.imagenext.core.model.SyncState
import com.imagenext.core.security.LockMethod
import com.imagenext.designsystem.ImageNextSurface

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logoutEvent by viewModel.logoutEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDisablePinDialog by remember { mutableStateOf(false) }
    var backupSettingsExpanded by remember { mutableStateOf(false) }
    var disablePin by remember { mutableStateOf("") }
    var disablePinError by remember { mutableStateOf<String?>(null) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        viewModel.onMediaPermissionResult(granted)
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Permission persistence may be unavailable on some providers.
            }
            val displayName = android.net.Uri.decode(uri.lastPathSegment.orEmpty())
                .substringAfterLast(':')
            viewModel.addLocalBackupFolder(uri.toString(), displayName)
        }
    }

    fun openDisablePinDialog() {
        disablePin = ""
        disablePinError = null
        showDisablePinDialog = true
    }

    LaunchedEffect(logoutEvent) {
        if (logoutEvent) {
            viewModel.onLogoutHandled()
            onLogout()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (uiState.isLoading) {
            LoadingState()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // ── Account Section ──
                SectionHeader(text = "Account")
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(
                            icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = "Server",
                            value = uiState.serverUrl,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                        SettingsRow(
                            icon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = "User",
                            value = uiState.loginName,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                        SettingsRow(
                            icon = {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = connectionStatusIconTint(uiState.connectionStatus),
                                )
                            },
                            label = "Connection",
                            value = connectionStatusLabel(uiState.connectionStatus),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Sync Section ──
                SectionHeader(text = "Sync")
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(
                            icon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = "Status",
                            value = syncStateLabel(uiState.syncState),
                        )
                        if (!uiState.syncIssue.isNullOrBlank() &&
                            (uiState.syncState == SyncState.Failed || uiState.syncState == SyncState.Partial)
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val issueTint = if (uiState.syncState == SyncState.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = issueTint,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Last issue: ${uiState.syncIssue}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = issueTint,
                                )
                            }
                        }
                        if (uiState.syncState == SyncState.Failed || uiState.syncState == SyncState.Partial) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.retrySync() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry sync", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Backup Section ──
                SectionHeader(text = "Backup")
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Enable toggle — always visible
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Nextcloud backup",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                BackupStatusDisplay(state = uiState.backupSyncState)
                            }
                            Switch(
                                checked = uiState.backupPolicy.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !uiState.backupPolicy.backupRootSelectedByUser) {
                                        viewModel.openBackupFolderPicker()
                                        return@Switch
                                    }
                                    val requiresMediaPermission = enabled &&
                                        uiState.backupPolicy.sourceScope == BackupSourceScope.FULL_LIBRARY &&
                                        !uiState.hasMediaPermission
                                    if (requiresMediaPermission) {
                                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            arrayOf(
                                                android.Manifest.permission.READ_MEDIA_IMAGES,
                                                android.Manifest.permission.READ_MEDIA_VIDEO,
                                            )
                                        } else {
                                            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                        }
                                        mediaPermissionLauncher.launch(permissions)
                                    } else {
                                        viewModel.updateBackupPolicy { it.copy(enabled = enabled) }
                                    }
                                },
                            )
                        }

                        // Destination — always visible
                        Spacer(modifier = Modifier.height(12.dp))
                        BackupDestinationPicker(
                            value = uiState.backupPolicy.backupRoot,
                            isSelectedByUser = uiState.backupPolicy.backupRootSelectedByUser,
                            needsReselection = uiState.backupRootNeedsReselection,
                            onOpenPicker = { viewModel.openBackupFolderPicker() },
                        )
                        if (uiState.backupRootNeedsReselection) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (uiState.backupPolicy.backupRootSelectedByUser) {
                                    "Current backup folder was not found on server. Please select a new folder."
                                } else {
                                    "Select a backup destination to enable backup."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = { viewModel.openBackupFolderPicker() },
                            ) {
                                Text("Select backup folder")
                            }
                        }

                        if (uiState.backupPolicy.enabled) {
                            // Media permission warning — always visible when enabled
                            if (
                                uiState.backupPolicy.sourceScope == BackupSourceScope.FULL_LIBRARY &&
                                !uiState.hasMediaPermission
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            arrayOf(
                                                android.Manifest.permission.READ_MEDIA_IMAGES,
                                                android.Manifest.permission.READ_MEDIA_VIDEO,
                                            )
                                        } else {
                                            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                        }
                                        mediaPermissionLauncher.launch(permissions)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Grant media permission")
                                }
                            }

                            // Configure toggle row
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { backupSettingsExpanded = !backupSettingsExpanded }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Configure backup",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Icon(
                                    imageVector = if (backupSettingsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (backupSettingsExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Collapsible settings
                            AnimatedVisibility(
                                visible = backupSettingsExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Column {
                                    // Source
                                    Text(
                                        text = "Source",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupSourceScope.entries.forEachIndexed { index, scope ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.sourceScope == scope,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(sourceScope = scope) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupSourceScope.entries.size),
                                                label = { Text(sourceScopeLabel(scope), maxLines = 1) },
                                            )
                                        }
                                    }

                                    if (uiState.backupPolicy.sourceScope == BackupSourceScope.SELECTED_FOLDERS) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = { folderPickerLauncher.launch(null) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Add folder to backup")
                                        }
                                        LocalBackupFolderPicker(
                                            selectedFolders = uiState.selectedLocalFolders,
                                            onRemoveFolder = { treeUri ->
                                                try {
                                                    context.contentResolver.releasePersistableUriPermission(
                                                        android.net.Uri.parse(treeUri),
                                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                                    )
                                                } catch (_: SecurityException) {
                                                    // Ignore if provider doesn't support permission release.
                                                }
                                                viewModel.removeLocalBackupFolder(treeUri)
                                            },
                                        )
                                    }

                                    // Upload structure
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Upload structure",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupUploadStructure.entries.forEachIndexed { index, structure ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.uploadStructure == structure,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(uploadStructure = structure) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupUploadStructure.entries.size),
                                                label = { Text(uploadStructureLabel(structure), maxLines = 1) },
                                            )
                                        }
                                    }

                                    // Sync mode
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Sync mode",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupSyncMode.entries.forEachIndexed { index, mode ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.syncMode == mode,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(syncMode = mode) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupSyncMode.entries.size),
                                                label = { Text(modeLabel(mode), maxLines = 1) },
                                            )
                                        }
                                    }

                                    if (uiState.backupPolicy.syncMode == BackupSyncMode.SCHEDULED) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            BackupScheduleType.entries.forEachIndexed { index, type ->
                                                SegmentedButton(
                                                    selected = uiState.backupPolicy.scheduleType == type,
                                                    onClick = { viewModel.updateBackupPolicy { it.copy(scheduleType = type) } },
                                                    shape = SegmentedButtonDefaults.itemShape(index, BackupScheduleType.entries.size),
                                                    label = { Text(scheduleTypeLabel(type), maxLines = 1) },
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (uiState.backupPolicy.scheduleType == BackupScheduleType.INTERVAL_HOURS) {
                                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                                listOf(2, 4, 6, 12, 24).forEachIndexed { index, hours ->
                                                    SegmentedButton(
                                                        selected = uiState.backupPolicy.scheduleIntervalHours == hours,
                                                        onClick = { viewModel.updateBackupPolicy { it.copy(scheduleIntervalHours = hours) } },
                                                        shape = SegmentedButtonDefaults.itemShape(index, 5),
                                                        label = { Text("${hours}h", maxLines = 1) },
                                                    )
                                                }
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(
                                                    value = uiState.backupPolicy.dailyHour.toString(),
                                                    onValueChange = { raw ->
                                                        raw.toIntOrNull()?.let { hour ->
                                                            viewModel.updateBackupPolicy { it.copy(dailyHour = hour.coerceIn(0, 23)) }
                                                        }
                                                    },
                                                    label = { Text("Hour") },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                OutlinedTextField(
                                                    value = uiState.backupPolicy.dailyMinute.toString(),
                                                    onValueChange = { raw ->
                                                        raw.toIntOrNull()?.let { minute ->
                                                            viewModel.updateBackupPolicy { it.copy(dailyMinute = minute.coerceIn(0, 59)) }
                                                        }
                                                    },
                                                    label = { Text("Minute") },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }

                                    // Network
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Network",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupNetworkPolicy.entries.forEachIndexed { index, policy ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.networkPolicy == policy,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(networkPolicy = policy) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupNetworkPolicy.entries.size),
                                                label = { Text(networkPolicyLabel(policy), maxLines = 1) },
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Allow roaming", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                        Switch(
                                            checked = uiState.backupPolicy.allowRoaming,
                                            onCheckedChange = { next ->
                                                viewModel.updateBackupPolicy { it.copy(allowRoaming = next) }
                                            },
                                        )
                                    }

                                    // Power
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Power",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupPowerPolicy.entries.forEachIndexed { index, power ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.powerPolicy == power,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(powerPolicy = power) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupPowerPolicy.entries.size),
                                                label = { Text(powerPolicyLabel(power), maxLines = 1) },
                                            )
                                        }
                                    }

                                    // Media type toggles
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Upload photos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                        Switch(
                                            checked = uiState.backupPolicy.mediaTypes.uploadPhotos,
                                            onCheckedChange = { next ->
                                                viewModel.updateBackupPolicy {
                                                    it.copy(mediaTypes = it.mediaTypes.copy(uploadPhotos = next))
                                                }
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Upload videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                        Switch(
                                            checked = uiState.backupPolicy.mediaTypes.uploadVideos,
                                            onCheckedChange = { next ->
                                                viewModel.updateBackupPolicy {
                                                    it.copy(mediaTypes = it.mediaTypes.copy(uploadVideos = next))
                                                }
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Auto-upload new files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                        Switch(
                                            checked = uiState.backupPolicy.autoUploadNewMedia,
                                            onCheckedChange = { next ->
                                                viewModel.updateBackupPolicy { it.copy(autoUploadNewMedia = next) }
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Auto-select backup root", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                        Switch(
                                            checked = uiState.backupPolicy.autoSelectBackupRoot,
                                            onCheckedChange = { next ->
                                                viewModel.updateBackupPolicy { it.copy(autoSelectBackupRoot = next) }
                                            },
                                        )
                                    }
                                    if (!uiState.backupPolicy.autoSelectBackupRoot) {
                                        Text(
                                            text = "Warning: if backup root is not selected for viewing, uploaded media may not appear in timeline.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }

                                    // Delete behavior
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Delete behavior",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        BackupDeletePolicy.entries.forEachIndexed { index, deletePolicy ->
                                            SegmentedButton(
                                                selected = uiState.backupPolicy.deletePolicy == deletePolicy,
                                                onClick = { viewModel.updateBackupPolicy { it.copy(deletePolicy = deletePolicy) } },
                                                shape = SegmentedButtonDefaults.itemShape(index, BackupDeletePolicy.entries.size),
                                                label = { Text(deletePolicyLabel(deletePolicy), maxLines = 1) },
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { viewModel.syncNowCombined() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Sync now (download + upload)")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Security Section ──
                SectionHeader(text = "Security")
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SecuritySettingsSection(
                            trustedCertificates = uiState.trustedCertificates,
                            isAppLockEnabled = uiState.isAppLockEnabled,
                            lockMethod = uiState.lockMethod,
                            isBiometricAvailable = uiState.isBiometricAvailable,
                            hasPin = uiState.hasPin,
                            onRevokeCertificate = { viewModel.revokeCertificate(it) },
                            onAppLockToggle = { enabled ->
                                if (!enabled) {
                                    val canUseBiometricForDisable =
                                        uiState.lockMethod == LockMethod.BIOMETRIC &&
                                            uiState.isBiometricAvailable &&
                                            activity != null
                                    if (canUseBiometricForDisable) {
                                        val prompt = BiometricPrompt.Builder(activity)
                                            .setTitle("Disable App Lock")
                                            .setSubtitle("Confirm your identity to disable app lock")
                                            .setNegativeButton(
                                                if (uiState.hasPin) "Use PIN" else "Cancel",
                                                ContextCompat.getMainExecutor(activity),
                                            ) { _, _ ->
                                                if (uiState.hasPin) {
                                                    openDisablePinDialog()
                                                }
                                            }
                                            .build()
                                        val hasBiometricPermission =
                                            ActivityCompat.checkSelfPermission(
                                                activity,
                                                android.Manifest.permission.USE_BIOMETRIC,
                                            ) == PackageManager.PERMISSION_GRANTED ||
                                                ActivityCompat.checkSelfPermission(
                                                    activity,
                                                    android.Manifest.permission.USE_FINGERPRINT,
                                                ) == PackageManager.PERMISSION_GRANTED
                                        if (hasBiometricPermission) {
                                            try {
                                                prompt.authenticate(
                                                    CancellationSignal(),
                                                    ContextCompat.getMainExecutor(activity),
                                                    object : BiometricPrompt.AuthenticationCallback() {
                                                        override fun onAuthenticationSucceeded(
                                                            result: BiometricPrompt.AuthenticationResult?,
                                                        ) {
                                                            viewModel.setAppLockEnabled(false)
                                                        }

                                                        override fun onAuthenticationError(
                                                            errorCode: Int,
                                                            errString: CharSequence?,
                                                        ) {
                                                            if (uiState.hasPin) {
                                                                openDisablePinDialog()
                                                            }
                                                        }
                                                    },
                                                )
                                            } catch (_: SecurityException) {
                                                if (uiState.hasPin) {
                                                    openDisablePinDialog()
                                                }
                                            }
                                        } else if (uiState.hasPin) {
                                            openDisablePinDialog()
                                        }
                                    } else {
                                        openDisablePinDialog()
                                    }
                                } else if (
                                    uiState.lockMethod == LockMethod.BIOMETRIC &&
                                    uiState.hasPin &&
                                    uiState.isBiometricAvailable &&
                                    activity != null
                                ) {
                                    val prompt = BiometricPrompt.Builder(activity)
                                        .setTitle("Enable App Lock")
                                        .setSubtitle("Confirm your biometric identity")
                                        .setNegativeButton(
                                            "Cancel",
                                            ContextCompat.getMainExecutor(activity),
                                        ) { _, _ -> }
                                        .build()
                                    val hasBiometricPermission =
                                        ActivityCompat.checkSelfPermission(
                                            activity,
                                            android.Manifest.permission.USE_BIOMETRIC,
                                        ) == PackageManager.PERMISSION_GRANTED ||
                                                ActivityCompat.checkSelfPermission(
                                                    activity,
                                                    android.Manifest.permission.USE_FINGERPRINT,
                                                ) == PackageManager.PERMISSION_GRANTED
                                    if (hasBiometricPermission) {
                                        try {
                                            prompt.authenticate(
                                                CancellationSignal(),
                                                ContextCompat.getMainExecutor(activity),
                                                object : BiometricPrompt.AuthenticationCallback() {
                                                    override fun onAuthenticationSucceeded(
                                                        result: BiometricPrompt.AuthenticationResult?,
                                                    ) {
                                                        viewModel.setAppLockEnabled(true)
                                                    }
                                                },
                                            )
                                        } catch (_: SecurityException) {
                                            viewModel.setAppLockEnabled(false)
                                        }
                                    } else {
                                        viewModel.setAppLockEnabled(false)
                                    }
                                } else {
                                    viewModel.setAppLockEnabled(true)
                                }
                            },
                            onLockMethodSelected = { method ->
                                if (method == LockMethod.BIOMETRIC) {
                                    val canEnableBiometricMethod =
                                        uiState.hasPin &&
                                            uiState.isBiometricAvailable &&
                                            activity != null

                                    if (canEnableBiometricMethod) {
                                        val hasBiometricPermission =
                                            ActivityCompat.checkSelfPermission(
                                                activity,
                                                android.Manifest.permission.USE_BIOMETRIC,
                                            ) == PackageManager.PERMISSION_GRANTED ||
                                                ActivityCompat.checkSelfPermission(
                                                    activity,
                                                    android.Manifest.permission.USE_FINGERPRINT,
                                                ) == PackageManager.PERMISSION_GRANTED

                                        if (hasBiometricPermission) {
                                            val prompt = BiometricPrompt.Builder(activity)
                                                .setTitle("Enable Biometric Unlock")
                                                .setSubtitle("Confirm your identity to use biometrics")
                                                .setNegativeButton(
                                                    "Cancel",
                                                    ContextCompat.getMainExecutor(activity),
                                                ) { _, _ -> }
                                                .build()

                                            try {
                                                prompt.authenticate(
                                                    CancellationSignal(),
                                                    ContextCompat.getMainExecutor(activity),
                                                    object : BiometricPrompt.AuthenticationCallback() {
                                                        override fun onAuthenticationSucceeded(
                                                            result: BiometricPrompt.AuthenticationResult?,
                                                        ) {
                                                            viewModel.setLockMethod(LockMethod.BIOMETRIC)
                                                        }
                                                    },
                                                )
                                            } catch (_: SecurityException) {
                                                // Keep previous method unchanged when biometric auth cannot start.
                                            }
                                        }
                                    }
                                } else {
                                    viewModel.setLockMethod(LockMethod.PIN)
                                }
                            },
                            onSavePin = { viewModel.savePin(it) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // ── Logout ──
                Button(
                    onClick = { showLogoutDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Log out", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("This will clear your local session and all cached data. You'll need to sign in again to access your photos.") },
            confirmButton = {
                TextButton(onClick = { viewModel.logout() }) {
                    Text("Log out", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = ImageNextSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDisablePinDialog) {
        AlertDialog(
            onDismissRequest = { showDisablePinDialog = false },
            title = { Text("Disable App Lock") },
            text = {
                Column {
                    Text("Enter your PIN to disable app lock.")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = disablePin,
                        onValueChange = {
                            disablePin = it.filter(Char::isDigit).take(8)
                            disablePinError = null
                        },
                        singleLine = true,
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (!disablePinError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = disablePinError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.verifyPin(disablePin)) {
                            viewModel.setAppLockEnabled(false)
                            showDisablePinDialog = false
                            disablePin = ""
                            disablePinError = null
                        } else {
                            disablePinError = "Incorrect PIN."
                        }
                    },
                    enabled = disablePin.length >= 4,
                ) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisablePinDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = ImageNextSurface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (uiState.isBackupFolderPickerVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackupFolderPicker() },
            title = { Text("Select backup destination") },
            text = {
                Column {
                    if (uiState.isBackupFolderPickerLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Loading folders...")
                        }
                    } else if (!uiState.backupFolderPickerError.isNullOrBlank()) {
                        Text(
                            text = uiState.backupFolderPickerError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.refreshBackupFolderOptions() }) {
                            Text("Retry")
                        }
                    } else if (uiState.backupFolderOptions.isEmpty()) {
                        Text(
                            text = "No folders found. Try refreshing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.refreshBackupFolderOptions() }) {
                            Text("Refresh")
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.backupFolderOptions.forEach { option ->
                                val isSelected = option.remotePath == uiState.backupPolicy.backupRoot
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.selectBackupRoot(
                                            folderRemotePath = option.remotePath,
                                            displayName = option.displayName,
                                        )
                                    },
                                    label = {
                                        Column {
                                            Text(option.displayName)
                                            Text(
                                                option.remotePath,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissBackupFolderPicker() }) {
                    Text("Close")
                }
            },
            dismissButton = null,
            containerColor = ImageNextSurface,
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun DiagnosticsSection(
    serverUrl: String,
    loginName: String,
    syncState: SyncState,
    folderCount: Int,
) {
    Column {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Local diagnostics only — never sent externally",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Redacted diagnostics info
        DiagnosticLine(label = "Server", value = serverUrl)
        DiagnosticLine(label = "User", value = loginName)
        DiagnosticLine(label = "Password", value = "***")
        DiagnosticLine(label = "Sync", value = syncStateLabel(syncState))
        DiagnosticLine(label = "Folders", value = "$folderCount")
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 1.dp, horizontal = 24.dp),
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )
}

private fun syncStateLabel(syncState: SyncState): String = when (syncState) {
    SyncState.Idle -> "Idle"
    SyncState.Running -> "Syncing…"
    SyncState.Partial -> "Partially complete"
    SyncState.Failed -> "Failed"
    SyncState.Completed -> "Up to date"
}

private fun connectionStatusLabel(connectionStatus: ConnectionStatus): String = when (connectionStatus) {
    ConnectionStatus.CONNECTED -> "Connected"
    ConnectionStatus.NOT_CONNECTED -> "Not connected"
}

private fun connectionStatusIconTint(connectionStatus: ConnectionStatus): Color = when (connectionStatus) {
    ConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
    ConnectionStatus.NOT_CONNECTED -> Color(0xFFC62828)
}

@Composable
private fun BackupStatusDisplay(state: com.imagenext.core.model.BackupSyncState) {
    val green = Color(0xFF4CAF50)
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val red = Color(0xFFEF5350)

    val (statusColor, statusLabel) = when (state.runState) {
        BackupRunState.COMPLETED -> green to "Completed"
        BackupRunState.RUNNING   -> gray  to "Running…"
        BackupRunState.FAILED    -> red   to "Failed"
        BackupRunState.IDLE      -> gray  to "Idle"
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Row 1: status dot + label, with live pending/failed counts if relevant
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(statusColor)
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.pendingCount > 0) {
                Text(
                    text = "${state.pendingCount} pending",
                    style = MaterialTheme.typography.bodySmall,
                    color = gray,
                )
            }
            if (state.failedCount > 0) {
                Text(
                    text = "${state.failedCount} failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = red,
                )
            }
        }

        // Last run block — only shown after at least one run
        if (state.hasLastRun) {
            val (lastColor, lastLabel) = when (state.lastRunResult) {
                BackupRunState.COMPLETED -> green to "Last run completed"
                BackupRunState.FAILED    -> red   to "Last run failed"
                BackupRunState.RUNNING   -> gray  to "Last run interrupted"
                BackupRunState.IDLE      -> gray  to "Last run ended"
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 2: last run result label
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(lastColor)
                )
                Text(
                    text = lastLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = lastColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Row 3: timestamp — dot-width spacer keeps it flush with text above
            state.lastRunFinishedAt?.let { ts ->
                val formatted = remember(ts) {
                    java.text.SimpleDateFormat("MMM d, yyyy  h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(ts))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(modifier = Modifier.size(7.dp))
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = gray,
                    )
                }
            }

            // Row 4: stat pills — dot-width spacer keeps them flush with text above
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.size(7.dp))
                BackupStatPill(label = "added",   value = state.lastRunUploadedCount, valueColor = green, gray = gray)
                BackupStatPill(label = "skipped", value = state.lastRunSkippedCount,  valueColor = gray,  gray = gray)
                if (state.lastRunDeletedCount > 0) {
                    BackupStatPill(label = "deleted", value = state.lastRunDeletedCount, valueColor = gray, gray = gray)
                }
                if (state.lastRunFailedCount > 0) {
                    BackupStatPill(label = "failed", value = state.lastRunFailedCount, valueColor = red, gray = gray)
                }
            }
        }
    }
}

@Composable
private fun BackupStatPill(label: String, value: Int, valueColor: Color, gray: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = valueColor,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = gray,
        )
    }
}

private fun modeLabel(mode: BackupSyncMode): String = when (mode) {
    BackupSyncMode.MANUAL_ONLY -> "Manual"
    BackupSyncMode.SCHEDULED -> "Scheduled"
    BackupSyncMode.WHEN_CHARGING -> "Charging"
}

private fun scheduleTypeLabel(type: BackupScheduleType): String = when (type) {
    BackupScheduleType.INTERVAL_HOURS -> "Every X hours"
    BackupScheduleType.DAILY_TIME -> "Specific time"
}

private fun networkPolicyLabel(policy: BackupNetworkPolicy): String = when (policy) {
    BackupNetworkPolicy.WIFI_ONLY -> "WiFi only"
    BackupNetworkPolicy.WIFI_OR_MOBILE -> "WiFi/Mobile"
}

private fun powerPolicyLabel(policy: BackupPowerPolicy): String = when (policy) {
    BackupPowerPolicy.REQUIRE_CHARGING -> "Charging"
    BackupPowerPolicy.REQUIRE_DEVICE_IDLE -> "Idle"
    BackupPowerPolicy.NONE -> "None"
}

private fun deletePolicyLabel(policy: BackupDeletePolicy): String = when (policy) {
    BackupDeletePolicy.APPEND_ONLY -> "Never delete"
    BackupDeletePolicy.MIRROR_DELETE -> "Mirror deletes"
}

private fun sourceScopeLabel(scope: BackupSourceScope): String = when (scope) {
    BackupSourceScope.FULL_LIBRARY -> "Full library"
    BackupSourceScope.SELECTED_FOLDERS -> "Selected folders"
}

private fun uploadStructureLabel(structure: BackupUploadStructure): String = when (structure) {
    BackupUploadStructure.FLAT_FOLDER -> "Flat folder"
    BackupUploadStructure.YEAR_MONTH_FOLDERS -> "Year/Month"
}

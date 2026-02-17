package com.imagenext.feature.settings

import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                                    viewModel.setAppLockEnabled(false)
                                } else if (
                                    uiState.lockMethod == LockMethod.BIOMETRIC &&
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
                            onLockMethodSelected = { viewModel.setLockMethod(it) },
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

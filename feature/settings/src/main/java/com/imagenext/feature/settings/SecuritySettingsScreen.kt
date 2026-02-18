package com.imagenext.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.imagenext.core.security.CertificateTrustStore
import com.imagenext.core.security.LockMethod
import com.imagenext.designsystem.ImageNextSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Security controls UI section embedded in the Settings screen.
 *
 * Provides:
 * - Trusted certificate list with revoke buttons
 * - App lock toggle
 *
 * All controls are explicit and auditable. Trust decisions
 * require user action and can be reviewed/revoked at any time.
 */
@Composable
fun SecuritySettingsSection(
    trustedCertificates: List<CertificateTrustStore.TrustedCertificate>,
    isAppLockEnabled: Boolean,
    lockMethod: LockMethod,
    isBiometricAvailable: Boolean,
    hasPin: Boolean,
    onRevokeCertificate: (String) -> Unit,
    onAppLockToggle: (Boolean) -> Unit,
    onLockMethodSelected: (LockMethod) -> Unit,
    onSavePin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var pendingBiometricEnable by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // App Lock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "App Lock",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Biometric authentication",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Switch(
                checked = isAppLockEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && lockMethod == LockMethod.PIN && !hasPin) {
                        // Bootstrap PIN-based lock setup instead of silently rejecting enable.
                        showPinDialog = true
                    } else {
                        onAppLockToggle(enabled)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            )
        }

        if (isAppLockEnabled) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.padding(start = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = lockMethod == LockMethod.PIN,
                    onClick = { onLockMethodSelected(LockMethod.PIN) },
                    label = { Text("PIN") },
                    leadingIcon = {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
                if (isBiometricAvailable) {
                    FilterChip(
                        selected = lockMethod == LockMethod.BIOMETRIC,
                        onClick = {
                            if (!hasPin) {
                                pendingBiometricEnable = true
                                showPinDialog = true
                            } else {
                                onLockMethodSelected(LockMethod.BIOMETRIC)
                            }
                        },
                        label = { Text("Biometric") },
                        leadingIcon = {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            if (lockMethod == LockMethod.PIN || !hasPin) {
                Text(
                    text = if (hasPin) "Change PIN" else "Set PIN",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 52.dp, top = 10.dp)
                        .clickable { showPinDialog = true },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Trusted Certificates Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Trusted Certificates",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (trustedCertificates.isEmpty()) {
            Text(
                text = "No manually trusted certificates",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 52.dp, top = 4.dp),
            )
        } else {
            Column(modifier = Modifier.padding(start = 52.dp)) {
                trustedCertificates.forEach { cert ->
                    TrustedCertificateRow(
                        certificate = cert,
                        onRevoke = { onRevokeCertificate(cert.fingerprint) },
                    )
                }
            }
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = {
                pendingBiometricEnable = false
                showPinDialog = false
            },
            onSave = { pin ->
                onSavePin(pin)
                if (pendingBiometricEnable) {
                    onLockMethodSelected(LockMethod.BIOMETRIC)
                    pendingBiometricEnable = false
                }
                showPinDialog = false
            },
        )
    }
}

@Composable
private fun PinDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val valid = pin.length >= 4

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set PIN") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                singleLine = true,
                label = { Text("PIN (4-8 digits)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pin) },
                enabled = valid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = ImageNextSurface,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun TrustedCertificateRow(
    certificate: CertificateTrustStore.TrustedCertificate,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val trustedDate = dateFormat.format(Date(certificate.trustedAt))
    val shortFingerprint = if (certificate.fingerprint.length > 16) {
        "${certificate.fingerprint.take(6)}…${certificate.fingerprint.takeLast(6)}"
    } else {
        certificate.fingerprint
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = certificate.host,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$shortFingerprint · $trustedDate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onRevoke, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Revoke trust",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            )
        }
    }
}

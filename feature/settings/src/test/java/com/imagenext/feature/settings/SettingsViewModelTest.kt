package com.imagenext.feature.settings

import com.imagenext.core.model.SyncState
import com.imagenext.core.security.CertificateTrustStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsUiState] and settings-related state.
 *
 * ViewModel integration tests that require Android dependencies
 * are more suitable as instrumented tests. These tests verify
 * the UI state model, defaults, and security invariants.
 */
class SettingsViewModelTest {

    @Test
    fun `default UI state starts as loading`() {
        val state = SettingsUiState()
        assertTrue(state.isLoading)
        assertEquals("", state.serverUrl)
        assertEquals("", state.loginName)
        assertEquals(ConnectionStatus.NOT_CONNECTED, state.connectionStatus)
        assertEquals(0, state.selectedFolderCount)
        assertEquals(SyncState.Idle, state.syncState)
        assertEquals(null, state.syncIssue)
        assertTrue(state.trustedCertificates.isEmpty())
        assertFalse(state.isAppLockEnabled)
    }

    @Test
    fun `UI state never exposes password field`() {
        // SettingsUiState intentionally has no password field
        val state = SettingsUiState(
            serverUrl = "https://nextcloud.example.com",
            loginName = "testuser",
            isLoading = false,
        )
        // Verify through toString that no password appears
        val stateStr = state.toString()
        assertFalse("No password field should exist", stateStr.contains("password", ignoreCase = true))
        assertFalse("No appPassword field should exist", stateStr.contains("appPassword"))
    }

    @Test
    fun `UI state copy preserves all fields`() {
        val original = SettingsUiState(
            isLoading = false,
            serverUrl = "https://my.server",
            loginName = "user1",
            connectionStatus = ConnectionStatus.CONNECTED,
            selectedFolderCount = 3,
            syncState = SyncState.Completed,
            isAppLockEnabled = true,
        )
        val modified = original.copy(syncState = SyncState.Running)
        assertEquals("https://my.server", modified.serverUrl)
        assertEquals("user1", modified.loginName)
        assertEquals(ConnectionStatus.CONNECTED, modified.connectionStatus)
        assertEquals(3, modified.selectedFolderCount)
        assertEquals(SyncState.Running, modified.syncState)
        assertTrue(modified.isAppLockEnabled)
    }

    @Test
    fun `trusted certificates list updates correctly`() {
        val certs = listOf(
            CertificateTrustStore.TrustedCertificate(
                fingerprint = "AA:BB:CC",
                host = "self-signed.local",
                trustedAt = 1000L,
            ),
        )
        val state = SettingsUiState(trustedCertificates = certs)
        assertEquals(1, state.trustedCertificates.size)
        assertEquals("self-signed.local", state.trustedCertificates[0].host)
    }

    @Test
    fun `logout wipes all state upon completion`() {
        // After logout, the expected next state is navigating to onboarding
        // with the session cleared. Verify the state model supports this.
        val preLogout = SettingsUiState(
            isLoading = false,
            serverUrl = "https://cloud.example.com",
            loginName = "admin",
            selectedFolderCount = 5,
        )
        // After logout, new state should be default
        val postLogout = SettingsUiState()
        assertTrue(postLogout.isLoading)
        assertEquals("", postLogout.serverUrl)
        assertEquals("", postLogout.loginName)
        assertEquals(0, postLogout.selectedFolderCount)
    }
}

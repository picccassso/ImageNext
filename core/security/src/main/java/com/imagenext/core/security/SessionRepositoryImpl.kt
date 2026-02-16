package com.imagenext.core.security

import com.imagenext.core.model.AuthSession

/**
 * Secure session persistence backed by [CredentialVault].
 *
 * Read, write, and clear operations delegate to the keystore-encrypted vault.
 * Logout performs a complete credential wipe; session becomes irrecoverable.
 */
class SessionRepositoryImpl(
    private val vault: CredentialVault,
) : SessionRepository {

    override fun getSession(): AuthSession? = vault.readSession()

    override fun saveSession(session: AuthSession) {
        vault.storeSession(session)
    }

    override fun clearSession() {
        vault.clearSession()
    }

    override fun hasValidSession(): Boolean = vault.readSession() != null

    override fun savePendingPollState(pollEndpoint: String, pollToken: String, serverUrl: String) {
        vault.storePendingPollState(pollEndpoint, pollToken, serverUrl)
    }

    override fun getPendingPollState(): Triple<String, String, String>? =
        vault.readPendingPollState()

    override fun clearPendingPollState() {
        vault.clearPendingPollState()
    }
}

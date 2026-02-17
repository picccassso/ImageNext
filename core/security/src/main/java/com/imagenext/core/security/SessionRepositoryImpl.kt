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

    @Volatile
    private var cachedSession: AuthSession? = null

    @Volatile
    private var isCacheInitialized: Boolean = false

    override fun getSession(): AuthSession? {
        if (isCacheInitialized) return cachedSession

        return synchronized(this) {
            if (!isCacheInitialized) {
                cachedSession = vault.readSession()
                isCacheInitialized = true
            }
            cachedSession
        }
    }

    override fun saveSession(session: AuthSession) {
        vault.storeSession(session)
        cachedSession = session
        isCacheInitialized = true
    }

    override fun clearSession() {
        vault.clearSession()
        cachedSession = null
        isCacheInitialized = true
    }

    override fun hasValidSession(): Boolean = getSession() != null

    override fun savePendingPollState(pollEndpoint: String, pollToken: String, serverUrl: String) {
        vault.storePendingPollState(pollEndpoint, pollToken, serverUrl)
    }

    override fun getPendingPollState(): Triple<String, String, String>? =
        vault.readPendingPollState()

    override fun clearPendingPollState() {
        vault.clearPendingPollState()
    }
}

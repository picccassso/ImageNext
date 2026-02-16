package com.imagenext.core.security

import com.imagenext.core.model.AuthSession

/**
 * Session repository contract for managing authenticated session lifecycle.
 *
 * Implementations must guarantee:
 * - Credentials are stored securely (encrypted at rest).
 * - [clearSession] performs an irrecoverable wipe of all session data.
 * - No credential data is exposed in logs or diagnostics.
 */
interface SessionRepository {
    /** Returns the stored session, or null if no valid session exists. */
    fun getSession(): AuthSession?

    /** Persists the given session securely. */
    fun saveSession(session: AuthSession)

    /** Wipes all session data. After this call, [getSession] returns null. */
    fun clearSession()

    /** Returns true if a valid session is currently stored. */
    fun hasValidSession(): Boolean

    /** Persists Login Flow v2 poll state to survive process death. */
    fun savePendingPollState(pollEndpoint: String, pollToken: String, serverUrl: String)

    /** Returns pending poll state as (endpoint, token, serverUrl), or null if none. */
    fun getPendingPollState(): Triple<String, String, String>?

    /** Clears pending poll state after login success or cancellation. */
    fun clearPendingPollState()
}

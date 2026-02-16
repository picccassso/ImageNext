package com.imagenext.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.imagenext.core.model.AuthSession

/**
 * Keystore-backed secret storage for session credentials.
 *
 * Uses [EncryptedSharedPreferences] with an Android Keystore-managed [MasterKey]
 * to securely store authentication tokens. Credentials are encrypted at rest
 * and never appear in plaintext outside this class.
 *
 * Security properties:
 * - AES-256-GCM encryption for values.
 * - AES-256-SIV encryption for keys (deterministic, supports key lookup).
 * - Keys stored in Android Keystore (hardware-backed when available).
 */
class CredentialVault(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Stores the given session credentials securely.
     * Overwrites any previously stored session.
     */
    fun storeSession(session: AuthSession) {
        prefs.edit()
            .putString(KEY_SERVER_URL, session.serverUrl)
            .putString(KEY_LOGIN_NAME, session.loginName)
            .putString(KEY_APP_PASSWORD, session.appPassword)
            .putString(KEY_USER_ID, session.userId)
            .apply()
    }

    /**
     * Reads the stored session, or returns null if no session exists.
     */
    fun readSession(): AuthSession? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val loginName = prefs.getString(KEY_LOGIN_NAME, null) ?: return null
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null).orEmpty()

        return AuthSession(
            serverUrl = serverUrl,
            loginName = loginName,
            appPassword = appPassword,
            userId = userId,
        )
    }

    /**
     * Clears all stored credentials. After this call, [readSession] returns null.
     * This is the secure logout wipe operation.
     */
    fun clearSession() {
        prefs.edit().clear().apply()
    }

    /**
     * Stores Login Flow v2 poll state so polling can resume after process death.
     */
    fun storePendingPollState(pollEndpoint: String, pollToken: String, serverUrl: String) {
        prefs.edit()
            .putString(KEY_POLL_ENDPOINT, pollEndpoint)
            .putString(KEY_POLL_TOKEN, pollToken)
            .putString(KEY_POLL_SERVER_URL, serverUrl)
            .apply()
    }

    /**
     * Reads pending Login Flow v2 poll state, or null if none exists.
     *
     * @return Triple of (pollEndpoint, pollToken, serverUrl) or null.
     */
    fun readPendingPollState(): Triple<String, String, String>? {
        val endpoint = prefs.getString(KEY_POLL_ENDPOINT, null) ?: return null
        val token = prefs.getString(KEY_POLL_TOKEN, null) ?: return null
        val serverUrl = prefs.getString(KEY_POLL_SERVER_URL, null) ?: return null
        return Triple(endpoint, token, serverUrl)
    }

    /**
     * Clears pending poll state after successful login or explicit cancellation.
     */
    fun clearPendingPollState() {
        prefs.edit()
            .remove(KEY_POLL_ENDPOINT)
            .remove(KEY_POLL_TOKEN)
            .remove(KEY_POLL_SERVER_URL)
            .apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "imagenext_secure_credentials"
        const val KEY_SERVER_URL = "session_server_url"
        const val KEY_LOGIN_NAME = "session_login_name"
        const val KEY_APP_PASSWORD = "session_app_password"
        const val KEY_USER_ID = "session_user_id"
        const val KEY_POLL_ENDPOINT = "pending_poll_endpoint"
        const val KEY_POLL_TOKEN = "pending_poll_token"
        const val KEY_POLL_SERVER_URL = "pending_poll_server_url"
    }
}

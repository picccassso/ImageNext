package com.imagenext.core.model

/**
 * Authenticated session domain type.
 *
 * Represents a successfully authenticated Nextcloud session.
 * Tokens and passwords in this type must never appear in logs.
 *
 * @property serverUrl The normalized base URL of the Nextcloud instance (always HTTPS, no trailing slash).
 * @property loginName The login name returned by the auth flow or entered manually.
 * @property appPassword The app-specific password (never the user's main account password).
 * @property userId The Nextcloud user ID, if available from the auth response.
 */
data class AuthSession(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
    val userId: String = "",
) {
    /** Redacted [toString] to prevent credential leakage in logs. */
    override fun toString(): String =
        "AuthSession(serverUrl=$serverUrl, loginName=$loginName, appPassword=***, userId=$userId)"
}

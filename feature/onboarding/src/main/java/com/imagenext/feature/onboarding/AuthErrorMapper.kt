package com.imagenext.feature.onboarding

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps network and authentication exceptions to user-readable messages.
 *
 * Provides actionable guidance including 2FA-specific help for generating app passwords.
 */
object AuthErrorMapper {

    /**
     * Maps a throwable to a user-friendly error message.
     */
    fun mapError(error: Throwable?): String = when (error) {
        is UnknownHostException ->
            "Server not found. Please check the URL and your internet connection."

        is SocketTimeoutException ->
            "Connection timed out. The server may be temporarily unreachable. Please try again."

        is SSLException ->
            "Secure connection failed. The server may be using a self-signed certificate " +
                    "that is not yet trusted."

        is IOException ->
            "A network error occurred. Please check your internet connection and try again."

        else ->
            "An unexpected error occurred. Please try again."
    }

    /**
     * Returns 2FA guidance text for users who may need to generate an app password.
     */
    fun get2FAGuidance(): String =
        "If your account uses two-factor authentication, you need to create an app password. " +
                "Go to your Nextcloud Settings → Security → Devices & Sessions, " +
                "and create a new app password for ImageNext."

    /**
     * Maps an HTTP status code from auth attempts to a user message.
     */
    fun mapHttpError(statusCode: Int): String = when (statusCode) {
        401 -> "Invalid credentials. Please check your username and app password.\n\n${get2FAGuidance()}"
        403 -> "Access denied. Your app password may have been revoked."
        404 -> "Server endpoint not found. Please verify the server URL."
        429 -> "Too many login attempts. Please wait a moment and try again."
        503 -> "The server is temporarily unavailable. Please try again later."
        else -> "Authentication failed (HTTP $statusCode). Please try again."
    }
}

package com.imagenext.feature.onboarding

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Unit tests for [AuthErrorMapper].
 *
 * Validates that all known error types map to user-readable messages.
 */
class AuthErrorMapperTest {

    @Test
    fun `UnknownHostException maps to server not found message`() {
        val message = AuthErrorMapper.mapError(UnknownHostException("test"))
        assertTrue(message.contains("Server not found", ignoreCase = true))
    }

    @Test
    fun `SocketTimeoutException maps to timeout message`() {
        val message = AuthErrorMapper.mapError(SocketTimeoutException("test"))
        assertTrue(message.contains("timed out", ignoreCase = true))
    }

    @Test
    fun `SSLException maps to secure connection message`() {
        val message = AuthErrorMapper.mapError(SSLException("test"))
        assertTrue(message.contains("Secure connection", ignoreCase = true))
    }

    @Test
    fun `IOException maps to network error message`() {
        val message = AuthErrorMapper.mapError(IOException("test"))
        assertTrue(message.contains("network error", ignoreCase = true))
    }

    @Test
    fun `null error maps to generic message`() {
        val message = AuthErrorMapper.mapError(null)
        assertTrue(message.contains("unexpected", ignoreCase = true))
    }

    @Test
    fun `unknown exception maps to generic message`() {
        val message = AuthErrorMapper.mapError(RuntimeException("something"))
        assertTrue(message.contains("unexpected", ignoreCase = true))
    }

    @Test
    fun `2FA guidance mentions app password`() {
        val guidance = AuthErrorMapper.get2FAGuidance()
        assertTrue(guidance.contains("app password", ignoreCase = true))
        assertTrue(guidance.contains("Security", ignoreCase = true))
    }

    @Test
    fun `HTTP 401 maps to invalid credentials with 2FA guidance`() {
        val message = AuthErrorMapper.mapHttpError(401)
        assertTrue(message.contains("Invalid credentials", ignoreCase = true))
        assertTrue(message.contains("app password", ignoreCase = true))
    }

    @Test
    fun `HTTP 403 maps to access denied message`() {
        val message = AuthErrorMapper.mapHttpError(403)
        assertTrue(message.contains("Access denied", ignoreCase = true))
    }

    @Test
    fun `HTTP 404 maps to endpoint not found message`() {
        val message = AuthErrorMapper.mapHttpError(404)
        assertTrue(message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `HTTP 429 maps to rate limit message`() {
        val message = AuthErrorMapper.mapHttpError(429)
        assertTrue(message.contains("Too many", ignoreCase = true))
    }

    @Test
    fun `HTTP 503 maps to unavailable message`() {
        val message = AuthErrorMapper.mapHttpError(503)
        assertTrue(message.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `unknown HTTP status maps to generic message with code`() {
        val message = AuthErrorMapper.mapHttpError(500)
        assertTrue(message.contains("500"))
    }

    @Test
    fun `all error types produce non-null non-empty messages`() {
        val errors = listOf(
            UnknownHostException(), SocketTimeoutException(),
            SSLException("test"), IOException("test"),
            RuntimeException("test"), null,
        )
        errors.forEach { error ->
            val message = AuthErrorMapper.mapError(error)
            assertNotNull(message)
            assertTrue(message.isNotBlank())
        }
    }
}

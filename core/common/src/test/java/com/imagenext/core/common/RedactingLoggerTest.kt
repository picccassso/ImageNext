package com.imagenext.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RedactingLogger] redaction logic.
 *
 * Validates that sensitive fields are redacted while safe strings pass through.
 */
class RedactingLoggerTest {

    @Test
    fun `redact removes password values from key-value pairs`() {
        val input = "appPassword=mySecretToken123"
        val result = RedactingLogger.redact(input)
        assertFalse("Password should be redacted", result.contains("mySecretToken123"))
        assertTrue("Key should remain", result.contains("appPassword"))
    }

    @Test
    fun `redact removes token values from key-value pairs`() {
        val input = "token: abc-secure-token-xyz"
        val result = RedactingLogger.redact(input)
        assertFalse("Token should be redacted", result.contains("abc-secure-token-xyz"))
        assertTrue("Key should remain", result.contains("token"))
    }

    @Test
    fun `redact removes secret values`() {
        val input = "secret=super_secret_value_123"
        val result = RedactingLogger.redact(input)
        assertFalse("Secret should be redacted", result.contains("super_secret_value_123"))
    }

    @Test
    fun `redact removes Authorization header values`() {
        val input = "Authorization: Basic dXNlcjpwYXNz"
        val result = RedactingLogger.redact(input)
        assertFalse("Auth token should be redacted", result.contains("dXNlcjpwYXNz"))
        assertTrue("Authorization key should remain", result.contains("Authorization"))
    }

    @Test
    fun `redact removes Bearer token values`() {
        val input = "Authorization=Bearer eyJhbGciOiJIUzI1NiJ9"
        val result = RedactingLogger.redact(input)
        assertFalse("Bearer token should be redacted", result.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redact masks URL credentials`() {
        val input = "Connecting to https://user:secretpass@nextcloud.example.com/dav"
        val result = RedactingLogger.redact(input)
        assertFalse("URL password should be redacted", result.contains("secretpass"))
        assertTrue("URL structure should remain", result.contains("https://"))
    }

    @Test
    fun `redact passes safe strings through unchanged`() {
        val input = "Sync completed for folder /Photos with 42 items"
        val result = RedactingLogger.redact(input)
        assertEquals("Safe string should be unchanged", input, result)
    }

    @Test
    fun `redact handles empty string`() {
        val result = RedactingLogger.redact("")
        assertEquals("", result)
    }

    @Test
    fun `redact handles multiple sensitive values in one message`() {
        val input = "password=abc123 token=xyz789 status=ok"
        val result = RedactingLogger.redact(input)
        assertFalse("Password should be redacted", result.contains("abc123"))
        assertFalse("Token should be redacted", result.contains("xyz789"))
        assertTrue("Non-sensitive value should remain", result.contains("status=ok"))
    }
}

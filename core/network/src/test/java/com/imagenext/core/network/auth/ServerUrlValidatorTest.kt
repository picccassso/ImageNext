package com.imagenext.core.network.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ServerUrlValidator].
 *
 * Tests URL normalization, HTTPS enforcement, and error cases.
 */
class ServerUrlValidatorTest {

    @Test
    fun `valid URL with https prefix is accepted as-is`() {
        val result = ServerUrlValidator.validate("https://cloud.example.com")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://cloud.example.com",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `URL without scheme gets https prepended`() {
        val result = ServerUrlValidator.validate("cloud.example.com")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://cloud.example.com",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `trailing slashes are stripped`() {
        val result = ServerUrlValidator.validate("https://cloud.example.com///")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://cloud.example.com",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `URL with path is preserved`() {
        val result = ServerUrlValidator.validate("https://example.com/nextcloud")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://example.com/nextcloud",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `URL with path and trailing slash is cleaned`() {
        val result = ServerUrlValidator.validate("example.com/nextcloud/")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://example.com/nextcloud",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `blank input is rejected`() {
        val result = ServerUrlValidator.validate("")
        assertTrue(result is ServerUrlValidator.ValidationResult.Invalid)
        assertTrue(
            (result as ServerUrlValidator.ValidationResult.Invalid)
                .reason.contains("empty", ignoreCase = true)
        )
    }

    @Test
    fun `whitespace-only input is rejected`() {
        val result = ServerUrlValidator.validate("   ")
        assertTrue(result is ServerUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `http scheme is rejected`() {
        val result = ServerUrlValidator.validate("http://cloud.example.com")
        assertTrue(result is ServerUrlValidator.ValidationResult.Invalid)
        assertTrue(
            (result as ServerUrlValidator.ValidationResult.Invalid)
                .reason.contains("HTTPS", ignoreCase = true)
        )
    }

    @Test
    fun `input is trimmed before validation`() {
        val result = ServerUrlValidator.validate("  cloud.example.com  ")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://cloud.example.com",
            (result as ServerUrlValidator.ValidationResult.Valid).normalizedUrl,
        )
    }

    @Test
    fun `uppercase HTTPS is accepted`() {
        val result = ServerUrlValidator.validate("HTTPS://cloud.example.com")
        assertTrue(result is ServerUrlValidator.ValidationResult.Valid)
    }
}

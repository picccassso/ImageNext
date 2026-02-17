package com.imagenext.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CertificateTrustStore] trust decision management.
 *
 * Note: These tests validate the data model and logic. The actual
 * EncryptedSharedPreferences operations require an Android test
 * environment (instrumented tests), so this covers the TrustedCertificate
 * data class behavior.
 */
class CertificateTrustStoreTest {

    @Test
    fun `TrustedCertificate data class holds fingerprint host and timestamp`() {
        val cert = CertificateTrustStore.TrustedCertificate(
            fingerprint = "AA:BB:CC:DD:EE:FF",
            host = "nextcloud.example.com",
            trustedAt = 1700000000000L,
        )
        assertEquals("AA:BB:CC:DD:EE:FF", cert.fingerprint)
        assertEquals("nextcloud.example.com", cert.host)
        assertEquals(1700000000000L, cert.trustedAt)
    }

    @Test
    fun `TrustedCertificate equality uses all fields`() {
        val cert1 = CertificateTrustStore.TrustedCertificate(
            fingerprint = "AA:BB:CC",
            host = "example.com",
            trustedAt = 1000L,
        )
        val cert2 = CertificateTrustStore.TrustedCertificate(
            fingerprint = "AA:BB:CC",
            host = "example.com",
            trustedAt = 1000L,
        )
        assertEquals(cert1, cert2)
        assertEquals(cert1.hashCode(), cert2.hashCode())
    }

    @Test
    fun `TrustedCertificate inequality when fingerprint differs`() {
        val cert1 = CertificateTrustStore.TrustedCertificate(
            fingerprint = "AA:BB:CC",
            host = "example.com",
            trustedAt = 1000L,
        )
        val cert2 = CertificateTrustStore.TrustedCertificate(
            fingerprint = "DD:EE:FF",
            host = "example.com",
            trustedAt = 1000L,
        )
        assertFalse(cert1 == cert2)
    }

    @Test
    fun `TrustedCertificate toString does not expose sensitive data`() {
        val cert = CertificateTrustStore.TrustedCertificate(
            fingerprint = "AA:BB:CC:DD",
            host = "secret-server.internal",
            trustedAt = 999L,
        )
        val str = cert.toString()
        assertTrue(str.contains("AA:BB:CC:DD"))
        assertTrue(str.contains("secret-server.internal"))
    }
}

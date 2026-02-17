package com.imagenext.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Trusted certificate persistence and lookup.
 *
 * Stores user-accepted certificate fingerprints in an encrypted SharedPreferences
 * file, separate from session credentials. This enables self-signed server
 * support with explicit trust decisions.
 *
 * Trust decisions require explicit user confirmation and can be reviewed/revoked.
 */
class CertificateTrustStore(context: Context) {

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
     * Data class representing a trusted certificate entry.
     *
     * @property fingerprint SHA-256 fingerprint of the certificate.
     * @property host The host this certificate was trusted for.
     * @property trustedAt Timestamp when the trust decision was made (epoch millis).
     */
    data class TrustedCertificate(
        val fingerprint: String,
        val host: String,
        val trustedAt: Long,
    )

    /**
     * Records a trust decision for a certificate fingerprint.
     *
     * User must have explicitly confirmed the fingerprint before calling this.
     *
     * @param fingerprint SHA-256 fingerprint of the certificate.
     * @param host The hostname this certificate is trusted for.
     */
    fun trustCertificate(fingerprint: String, host: String) {
        val value = "$host|${System.currentTimeMillis()}"
        prefs.edit()
            .putString(keyForFingerprint(fingerprint), value)
            .apply()
    }

    /**
     * Checks whether a given certificate fingerprint is currently trusted.
     */
    fun isTrusted(fingerprint: String): Boolean {
        return prefs.contains(keyForFingerprint(fingerprint))
    }

    /**
     * Returns all currently trusted certificates for review.
     */
    fun getAll(): List<TrustedCertificate> {
        return prefs.all
            .filter { (key, _) -> key.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val fingerprint = key.removePrefix(KEY_PREFIX)
                val parts = (value as? String)?.split("|", limit = 2) ?: return@mapNotNull null
                if (parts.size < 2) return@mapNotNull null
                TrustedCertificate(
                    fingerprint = fingerprint,
                    host = parts[0],
                    trustedAt = parts[1].toLongOrNull() ?: 0L,
                )
            }
    }

    /**
     * Revokes trust for a previously trusted certificate.
     *
     * After revocation, [isTrusted] will return false for this fingerprint.
     */
    fun revoke(fingerprint: String) {
        prefs.edit()
            .remove(keyForFingerprint(fingerprint))
            .apply()
    }

    /**
     * Clears all trusted certificates. Used during secure logout.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun keyForFingerprint(fingerprint: String): String = "$KEY_PREFIX$fingerprint"

    private companion object {
        const val PREFS_FILE_NAME = "imagenext_trusted_certificates"
        const val KEY_PREFIX = "cert_"
    }
}

package com.imagenext.core.network.auth

import java.net.URI

/**
 * Utility for normalizing and validating Nextcloud server URLs.
 *
 * Enforces HTTPS convention and produces clean base URLs free of trailing slashes.
 */
object ServerUrlValidator {

    /** Result of URL validation. */
    sealed interface ValidationResult {
        data class Valid(val normalizedUrl: String) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    /**
     * Normalizes and validates the given server URL input.
     *
     * Rules:
     * 1. Blank input is rejected.
     * 2. If no scheme is present, `https://` is prepended.
     * 3. `http://` scheme is rejected (cleartext not allowed).
     * 4. Trailing slashes are stripped.
     * 5. URL must parse as a valid URI with a host.
     */
    fun validate(input: String): ValidationResult {
        val trimmed = input.trim()

        if (trimmed.isBlank()) {
            return ValidationResult.Invalid("Server URL cannot be empty.")
        }

        // Reject cleartext HTTP
        if (trimmed.lowercase().startsWith("http://")) {
            return ValidationResult.Invalid(
                "Insecure HTTP connections are not allowed. Please use HTTPS."
            )
        }

        // Prepend https:// if no scheme is present
        val withScheme = if (!trimmed.lowercase().startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        // Strip trailing slashes
        val cleaned = withScheme.trimEnd('/')

        // Validate as URI
        return try {
            val uri = URI(cleaned)
            if (uri.host.isNullOrBlank()) {
                ValidationResult.Invalid("Invalid server URL: no hostname found.")
            } else {
                ValidationResult.Valid(cleaned)
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Invalid server URL format: ${e.message}")
        }
    }
}

package com.imagenext.core.model

/**
 * Normalized server configuration domain type.
 *
 * Represents a validated and normalized Nextcloud server URL.
 *
 * @property baseUrl The normalized base URL (always HTTPS, no trailing slash).
 */
data class ServerConfig(
    val baseUrl: String,
)

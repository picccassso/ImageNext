package com.imagenext.core.model

/**
 * Selected folder domain model.
 *
 * Represents a user-selected Nextcloud folder that should be monitored for media content.
 *
 * @property remotePath The full WebDAV remote path of the folder.
 * @property displayName The human-readable folder name for UI display.
 */
data class SelectedFolder(
    val remotePath: String,
    val displayName: String,
)

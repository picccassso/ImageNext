package com.imagenext.core.model

/**
 * Album domain model.
 *
 * Represents a user-managed local album.
 *
 * @property id Stable local album identifier.
 * @property displayName The name of the album for display.
 * @property mediaCount The number of media items in this album.
 * @property coverThumbnailPath Local path to the thumbnail of the cover image, if available.
 * @property isSystem Whether this is an auto-managed smart album.
 */
data class Album(
    val id: Long,
    val displayName: String,
    val mediaCount: Int,
    val coverThumbnailPath: String? = null,
    val isSystem: Boolean = false,
)

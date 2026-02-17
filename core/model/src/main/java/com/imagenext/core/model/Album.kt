package com.imagenext.core.model

/**
 * Album domain model.
 *
 * Represents an album of photos, mapped to a Nextcloud folder.
 *
 * @property folderPath The remote path of the folder that represents this album.
 * @property displayName The name of the album for display.
 * @property mediaCount The number of media items in this album.
 * @property coverThumbnailPath Local path to the thumbnail of the cover image, if available.
 */
data class Album(
    val folderPath: String,
    val displayName: String,
    val mediaCount: Int,
    val coverThumbnailPath: String? = null,
    val coverRemotePath: String? = null,
)

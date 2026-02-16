package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for local media metadata storage.
 *
 * Stores metadata about media files discovered from Nextcloud WebDAV.
 * Supports timeline queries and viewer detail lookups.
 */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    /** Full WebDAV remote path — unique identifier. */
    @PrimaryKey
    val remotePath: String,

    /** Display name of the file. */
    val fileName: String,

    /** MIME type (e.g., "image/jpeg"). */
    val mimeType: String,

    /** File size in bytes. */
    val size: Long,

    /** Last modification timestamp (epoch millis). */
    val lastModified: Long,

    /** Server ETag for change detection. */
    val etag: String,

    /** Local path to cached thumbnail, or null if not yet fetched. */
    val thumbnailPath: String? = null,

    /** Parent folder's remote path. */
    val folderPath: String,
)

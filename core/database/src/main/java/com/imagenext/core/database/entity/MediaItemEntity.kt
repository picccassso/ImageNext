package com.imagenext.core.database.entity

import androidx.room.ColumnInfo
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

    /** Best-effort capture timestamp (epoch millis), if known. */
    val captureTimestamp: Long? = null,

    /** Server ETag for change detection. */
    val etag: String,

    /** Local path to cached thumbnail, or null if not yet fetched. */
    val thumbnailPath: String? = null,

    /** Thumbnail lifecycle state (`PENDING`, `READY`, `FAILED`). */
    @ColumnInfo(defaultValue = THUMBNAIL_STATUS_PENDING)
    val thumbnailStatus: String = THUMBNAIL_STATUS_PENDING,

    /** Number of failed thumbnail fetch attempts. */
    @ColumnInfo(defaultValue = "0")
    val thumbnailRetryCount: Int = 0,

    /** Last thumbnail fetch error category (redacted). */
    val thumbnailLastError: String? = null,

    /** Parent folder's remote path. */
    val folderPath: String,
)

const val THUMBNAIL_STATUS_PENDING = "PENDING"
const val THUMBNAIL_STATUS_READY = "READY"
const val THUMBNAIL_STATUS_FAILED = "FAILED"

package com.imagenext.core.model

/**
 * Media item domain model.
 *
 * Represents a single media file discovered from a Nextcloud folder.
 * Used for timeline display and viewer access.
 *
 * @property remotePath Full WebDAV remote path to the file.
 * @property fileName Display name of the file.
 * @property mimeType MIME type of the media (e.g., "image/jpeg").
 * @property size File size in bytes.
 * @property lastModified Last modification timestamp (epoch millis).
 * @property captureTimestamp Best-effort capture timestamp (epoch millis), if available.
 * @property etag Server-provided ETag for change detection.
 * @property fileId Nextcloud file identifier used for preview APIs, if known.
 * @property thumbnailPath Local filesystem path to the cached thumbnail, or null if not yet fetched.
 * @property folderPath The parent folder's remote path this item belongs to.
 */
data class MediaItem(
    val remotePath: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val captureTimestamp: Long? = null,
    val etag: String,
    val fileId: Long? = null,
    val thumbnailPath: String? = null,
    val folderPath: String,
) {
    val mediaKind: MediaKind
        get() = MediaKind.from(mimeType = mimeType, fileName = fileName)

    val isImage: Boolean
        get() = mediaKind == MediaKind.IMAGE

    val isVideo: Boolean
        get() = mediaKind == MediaKind.VIDEO
}

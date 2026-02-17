package com.imagenext.core.data

import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.model.MediaItem
import com.imagenext.core.security.SessionRepository
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Viewer data access contract for current and adjacent assets.
 *
 * Provides lookup of individual media items and ordered timeline
 * lists for the fullscreen viewer's navigation and prefetch logic.
 */
open class ViewerRepository(
    private val mediaDao: MediaDao,
    private val sessionRepository: SessionRepository? = null,
) {

    /**
     * Authenticated remote source contract for loading media.
     *
     * `fullResUrl` is the canonical WebDAV file URL.
     * `previewUrl` is a smaller preview fallback for faster progressive display.
     */
    data class RemoteImageSource(
        val remotePath: String,
        val fullResUrl: String,
        val previewUrl: String,
        val authHeader: String,
    )

    /** Returns a single media item by its remote path, or null if not found. */
    open suspend fun getMediaByPath(remotePath: String): MediaItem? {
        return mediaDao.getByRemotePath(remotePath)?.let {
            MediaItem(
                remotePath = it.remotePath,
                fileName = it.fileName,
                mimeType = it.mimeType,
                size = it.size,
                lastModified = it.lastModified,
                captureTimestamp = it.captureTimestamp,
                etag = it.etag,
                thumbnailPath = it.thumbnailPath,
                folderPath = it.folderPath,
            )
        }
    }

    /** Returns all media items ordered by timeline timestamp descending. */
    open suspend fun getAllMediaOrdered(): List<MediaItem> {
        return mediaDao.getAllMediaList().map {
            MediaItem(
                remotePath = it.remotePath,
                fileName = it.fileName,
                mimeType = it.mimeType,
                size = it.size,
                lastModified = it.lastModified,
                captureTimestamp = it.captureTimestamp,
                etag = it.etag,
                thumbnailPath = it.thumbnailPath,
                folderPath = it.folderPath,
            )
        }
    }

    /** Returns an authenticated remote image source for a media path, or null if no active session. */
    open fun getRemoteImageSource(remotePath: String): RemoteImageSource? {
        val session = sessionRepository?.getSession() ?: return null
        val cleanPath = remotePath.trimStart('/')
        val encodedPath = encodePathForWebDav(cleanPath)
        val fullResUrl = "${session.serverUrl}/remote.php/dav/files/${session.loginName}/$encodedPath"

        val encodedRemotePath = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val previewUrl = "${session.serverUrl}/index.php/core/preview" +
            "?file=$encodedRemotePath&x=$PREVIEW_SIZE&y=$PREVIEW_SIZE&a=1"

        val authHeader = buildBasicAuthHeader(
            username = session.loginName,
            password = session.appPassword,
        )

        return RemoteImageSource(
            remotePath = remotePath,
            fullResUrl = fullResUrl,
            previewUrl = previewUrl,
            authHeader = authHeader,
        )
    }

    /**
     * Returns authenticated image sources for adjacent items around the current index.
     * Used by the viewer for proactive prefetch.
     */
    open fun getAdjacentRemoteImageSources(
        items: List<MediaItem>,
        centerIndex: Int,
        window: Int = 1,
    ): List<RemoteImageSource> {
        if (items.isEmpty() || centerIndex !in items.indices || window <= 0) return emptyList()

        val start = (centerIndex - window).coerceAtLeast(0)
        val end = (centerIndex + window).coerceAtMost(items.lastIndex)
        return (start..end)
            .asSequence()
            .filter { it != centerIndex }
            .mapNotNull { index -> getRemoteImageSource(items[index].remotePath) }
            .toList()
    }

    private fun encodePathForWebDav(path: String): String {
        if (path.isBlank()) return ""
        return path
            .split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
    }

    private fun buildBasicAuthHeader(username: String, password: String): String {
        val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        return "Basic $encoded"
    }

    private companion object {
        const val PREVIEW_SIZE = 1024
    }
}

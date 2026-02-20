package com.imagenext.core.sync

import com.imagenext.core.database.dao.MediaPruneRef
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_PENDING
import com.imagenext.core.network.webdav.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibrarySyncReconcilePolicyTest {

    @Test
    fun `stale reconciliation deletes only scanned folders`() {
        val stale = computeStaleMediaRefsByFolder(
            scannedFolders = setOf("/Photos"),
            existingRefsByFolder = mapOf(
                "/Photos" to listOf(
                    MediaPruneRef("/Photos/a.jpg", null),
                    MediaPruneRef("/Photos/b.jpg", null),
                ),
                "/Videos" to listOf(
                    MediaPruneRef("/Videos/c.mp4", null),
                ),
            ),
            incomingRemotePathsByFolder = mapOf(
                "/Photos" to setOf("/Photos/a.jpg"),
                "/Videos" to emptySet(),
            ),
        )

        assertEquals(setOf("/Photos/b.jpg"), stale.map { it.remotePath }.toSet())
    }

    @Test
    fun `empty incoming for scanned folder prunes all existing rows`() {
        val stale = computeStaleMediaRefsByFolder(
            scannedFolders = setOf("/Photos"),
            existingRefsByFolder = mapOf(
                "/Photos" to listOf(
                    MediaPruneRef("/Photos/a.jpg", null),
                    MediaPruneRef("/Photos/b.jpg", null),
                ),
            ),
            incomingRemotePathsByFolder = emptyMap(),
        )

        assertEquals(
            setOf("/Photos/a.jpg", "/Photos/b.jpg"),
            stale.map { it.remotePath }.toSet(),
        )
    }

    @Test
    fun `folder path alias normalization collapses slash variants`() {
        val incoming = incomingRemotePathsByFolder(
            listOf(
                sampleEntity(remotePath = "/Photos/a.jpg", folderPath = "/Photos"),
                sampleEntity(remotePath = "/Photos/b.jpg", folderPath = "/Photos/"),
            )
        )

        assertEquals(setOf("/Photos"), incoming.keys)
        assertEquals(setOf("/Photos/a.jpg", "/Photos/b.jpg"), incoming["/Photos"])
        assertEquals(setOf("/Photos", "/Photos/"), folderPathAliases("/Photos/"))
    }

    @Test
    fun `missing folder purge query normalizes root and prefix`() {
        val query = buildMissingFolderPurgeQuery("/Photos/")
        assertEquals("/Photos", query.rootPath)
        assertEquals("/Photos/%", query.rootPrefixLike)
    }

    @Test
    fun `404 not found is treated as missing selected folder purge signal`() {
        val missing = WebDavClient.WebDavResult.Error(
            message = "missing",
            category = WebDavClient.WebDavResult.ErrorCategory.NOT_FOUND,
            httpStatusCode = 404,
        )
        val transient = WebDavClient.WebDavResult.Error(
            message = "timeout",
            category = WebDavClient.WebDavResult.ErrorCategory.TRANSIENT,
            httpStatusCode = 504,
        )

        assertTrue(shouldPurgeMissingSelectedFolder(missing))
        assertFalse(shouldPurgeMissingSelectedFolder(transient))
    }

    @Test
    fun `thumbnail cleanup deletes only files under cache root`() {
        val root = Files.createTempDirectory("library-sync-prune").toFile()
        val cacheDir = Files.createDirectory(root.toPath().resolve("cache")).toFile()
        val safeThumb = cacheDir.resolve("thumb_a.jpg").apply { writeText("a") }
        val outsideThumb = root.resolve("outside.jpg").apply { writeText("b") }

        try {
            val deleted = deleteThumbnailFilesUnderCache(
                thumbnailPaths = listOf(safeThumb.absolutePath, outsideThumb.absolutePath),
                cacheRootDir = cacheDir,
            )

            assertEquals(1, deleted)
            assertFalse(safeThumb.exists())
            assertTrue(outsideThumb.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `thumbnail presence check is true only for existing files within cache root`() {
        val root = Files.createTempDirectory("library-sync-thumb-check").toFile()
        val cacheDir = Files.createDirectory(root.toPath().resolve("cache")).toFile()
        val safeThumb = cacheDir.resolve("thumb_b.jpg").apply { writeText("x") }
        val outsideThumb = root.resolve("outside_b.jpg").apply { writeText("y") }

        try {
            assertTrue(
                isThumbnailFilePresentInCache(
                    thumbnailPath = safeThumb.absolutePath,
                    cacheRootDir = cacheDir,
                )
            )
            assertFalse(
                isThumbnailFilePresentInCache(
                    thumbnailPath = outsideThumb.absolutePath,
                    cacheRootDir = cacheDir,
                )
            )
            assertFalse(
                isThumbnailFilePresentInCache(
                    thumbnailPath = cacheDir.resolve("missing.jpg").absolutePath,
                    cacheRootDir = cacheDir,
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleEntity(remotePath: String, folderPath: String): MediaItemEntity {
        return MediaItemEntity(
            remotePath = remotePath,
            fileName = remotePath.substringAfterLast('/'),
            mimeType = "image/jpeg",
            size = 1L,
            lastModified = 0L,
            captureTimestamp = null,
            timelineSortKey = 0L,
            etag = "etag",
            fileId = null,
            thumbnailPath = null,
            thumbnailStatus = THUMBNAIL_STATUS_PENDING,
            thumbnailRetryCount = 0,
            thumbnailLastError = null,
            folderPath = folderPath,
        )
    }
}

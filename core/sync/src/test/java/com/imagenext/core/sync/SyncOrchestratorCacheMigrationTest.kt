package com.imagenext.core.sync

import com.imagenext.core.database.dao.ReadyThumbnailReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncOrchestratorCacheMigrationTest {

    @Test
    fun `ready thumbnail missing on disk is reset to pending`() = runTest {
        val root = Files.createTempDirectory("sync-migration-missing").toFile()
        try {
            val thumbnailDir = File(root, "thumbnails").apply { mkdirs() }
            val resetPaths = mutableListOf<String>()
            val missingPath = File(thumbnailDir, "thumb_missing.jpg").absolutePath

            val stats = reconcileReadyThumbnailReferences(
                references = listOf(
                    ReadyThumbnailReference(
                        remotePath = "/photos/a.jpg",
                        thumbnailPath = missingPath,
                    )
                ),
                thumbnailCacheDir = thumbnailDir,
                maxThumbnailBytes = 1_048_576L,
                resetThumbnailState = { remotePath -> resetPaths += remotePath },
            )

            assertEquals(1, stats.resetCount)
            assertEquals(0L, stats.deletedBytes)
            assertEquals(listOf("/photos/a.jpg"), resetPaths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `oversized ready thumbnail is deleted and reset`() = runTest {
        val root = Files.createTempDirectory("sync-migration-oversized").toFile()
        try {
            val thumbnailDir = File(root, "thumbnails").apply { mkdirs() }
            val oversizedFile = File(thumbnailDir, "thumb_large.jpg")
            oversizedFile.writeBytes(ByteArray(1_048_577))
            val resetPaths = mutableListOf<String>()

            val stats = reconcileReadyThumbnailReferences(
                references = listOf(
                    ReadyThumbnailReference(
                        remotePath = "/photos/b.jpg",
                        thumbnailPath = oversizedFile.absolutePath,
                    )
                ),
                thumbnailCacheDir = thumbnailDir,
                maxThumbnailBytes = 1_048_576L,
                resetThumbnailState = { remotePath -> resetPaths += remotePath },
            )

            assertEquals(1, stats.resetCount)
            assertTrue(stats.deletedBytes > 1_048_576L)
            assertFalse(oversizedFile.exists())
            assertEquals(listOf("/photos/b.jpg"), resetPaths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `valid ready thumbnail remains unchanged`() = runTest {
        val root = Files.createTempDirectory("sync-migration-valid").toFile()
        try {
            val thumbnailDir = File(root, "thumbnails").apply { mkdirs() }
            val validFile = File(thumbnailDir, "thumb_ok.jpg")
            validFile.writeBytes(ByteArray(8_192))
            val resetPaths = mutableListOf<String>()

            val stats = reconcileReadyThumbnailReferences(
                references = listOf(
                    ReadyThumbnailReference(
                        remotePath = "/photos/c.jpg",
                        thumbnailPath = validFile.absolutePath,
                    )
                ),
                thumbnailCacheDir = thumbnailDir,
                maxThumbnailBytes = 1_048_576L,
                resetThumbnailState = { remotePath -> resetPaths += remotePath },
            )

            assertEquals(0, stats.resetCount)
            assertEquals(0L, stats.deletedBytes)
            assertTrue(validFile.exists())
            assertTrue(resetPaths.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `thumbnail path outside cache directory is ignored`() = runTest {
        val root = Files.createTempDirectory("sync-migration-outside").toFile()
        try {
            val thumbnailDir = File(root, "thumbnails").apply { mkdirs() }
            val outsideDir = File(root, "outside").apply { mkdirs() }
            val outsideFile = File(outsideDir, "thumb_external.jpg")
            outsideFile.writeBytes(ByteArray(1_048_577))
            val resetPaths = mutableListOf<String>()

            val stats = reconcileReadyThumbnailReferences(
                references = listOf(
                    ReadyThumbnailReference(
                        remotePath = "/photos/d.jpg",
                        thumbnailPath = outsideFile.absolutePath,
                    )
                ),
                thumbnailCacheDir = thumbnailDir,
                maxThumbnailBytes = 1_048_576L,
                resetThumbnailState = { remotePath -> resetPaths += remotePath },
            )

            assertEquals(0, stats.resetCount)
            assertEquals(0L, stats.deletedBytes)
            assertTrue(outsideFile.exists())
            assertTrue(resetPaths.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy cache cleanup handles missing directories`() {
        val root = Files.createTempDirectory("sync-migration-cache-missing").toFile()
        try {
            val stats = clearLegacyRemoteImageDiskCaches(
                cacheRootDir = root,
                legacyDirNames = listOf("image_cache", "coil_image_cache"),
            )
            assertEquals(0, stats.clearedDirCount)
            assertEquals(0L, stats.deletedBytes)
        } finally {
            root.deleteRecursively()
        }
    }
}

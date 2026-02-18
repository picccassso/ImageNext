package com.imagenext.core.sync

import com.imagenext.core.model.BackupUploadStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPathResolverTest {

    @Test
    fun `resolveTargetRemoteFolder returns flat folder when configured`() {
        val folder = resolveTargetRemoteFolder(
            backupRoot = "/Photos/ImageNext Backup",
            uploadStructure = BackupUploadStructure.FLAT_FOLDER,
            captureTimestampMs = 1_735_696_800_000L,
        )

        assertEquals("/Photos/ImageNext Backup", folder)
    }

    @Test
    fun `resolveTargetRemoteFolder appends year and month when configured`() {
        val folder = resolveTargetRemoteFolder(
            backupRoot = "/Photos/ImageNext Backup",
            uploadStructure = BackupUploadStructure.YEAR_MONTH_FOLDERS,
            captureTimestampMs = 1_735_696_800_000L,
        )

        assertTrue(folder.startsWith("/Photos/ImageNext Backup/"))
        val segments = folder.split('/').filter { it.isNotBlank() }
        assertEquals(4, segments.size)
        assertTrue(segments.last().length == 2)
    }

    @Test
    fun `buildRemoteFilePath normalizes separators`() {
        val path = buildRemoteFilePath("Photos//ImageNext Backup//2026/01/", "IMG_1.jpg")
        assertEquals("/Photos/ImageNext Backup/2026/01/IMG_1.jpg", path)
    }

    @Test
    fun `retry delay grows for subsequent attempts`() {
        assertEquals(30_000L, computeRetryDelayMillis(1))
        assertEquals(120_000L, computeRetryDelayMillis(2))
        assertEquals(300_000L, computeRetryDelayMillis(3))
        assertEquals(300_000L, computeRetryDelayMillis(4))
    }
}

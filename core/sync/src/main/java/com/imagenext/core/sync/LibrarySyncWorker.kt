package com.imagenext.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_PENDING
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_READY
import com.imagenext.core.model.SyncState
import com.imagenext.core.network.webdav.WebDavClient
import com.imagenext.core.security.SessionRepository

/**
 * Background metadata indexing worker.
 *
 * Iterates selected folders, discovers media files via WebDAV PROPFIND,
 * and upserts media metadata to the local database. Uses sync checkpoints
 * for resumability and safe operation under network interruptions.
 *
 * Reports progress as worker output data for UI consumption.
 */
class LibrarySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val workerStartMs = SystemClock.elapsedRealtime()
        val database = AppDatabase.getInstance(applicationContext)
        val folderDao = database.folderDao()
        val mediaDao = database.mediaDao()

        // Retrieve session credentials
        val sessionRepo = SyncDependencies.getSessionRepository(applicationContext)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))
        val session = sessionRepo.getSession()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))

        val webDavClient = WebDavClient()
        val selectedFolders = folderDao.getSelectedFoldersList()

        if (selectedFolders.isEmpty()) {
            logPerf("library_sync_no_folders durationMs=${elapsedSince(workerStartMs)}")
            return Result.success(workDataOf(KEY_SYNCED_COUNT to 0))
        }

        logPerf("library_sync_start folders=${selectedFolders.size}")

        var totalSynced = 0
        var failedFolders = 0

        for ((index, folder) in selectedFolders.withIndex()) {
            // Check if we should stop
            if (isStopped) break

            // Report progress
            setProgress(
                workDataOf(
                    KEY_PROGRESS_CURRENT to index,
                    KEY_PROGRESS_TOTAL to selectedFolders.size,
                    KEY_CURRENT_FOLDER to folder.displayName,
                )
            )

            // Check checkpoint — skip if folder hasn't changed
            val checkpoint = folderDao.getCheckpoint(folder.remotePath)

            // Fetch media files from WebDAV
            val listStartMs = SystemClock.elapsedRealtime()
            val result = webDavClient.listMediaFiles(
                serverUrl = session.serverUrl,
                loginName = session.loginName,
                appPassword = session.appPassword,
                folderPath = folder.remotePath,
            )
            val listDurationMs = elapsedSince(listStartMs)

            when (result) {
                is WebDavClient.WebDavResult.Success -> {
                    val upsertStartMs = SystemClock.elapsedRealtime()
                    val existingByPath = mediaDao
                        .getByRemotePaths(result.data.map { it.remotePath })
                        .associateBy { it.remotePath }

                    val entities = result.data.map { item ->
                        val existing = existingByPath[item.remotePath]
                        val metadataUnchanged = existing != null &&
                            existing.etag == item.etag &&
                            existing.size == item.size &&
                            existing.lastModified == item.lastModified
                        val preserveThumbnail = metadataUnchanged && !existing.thumbnailPath.isNullOrBlank()

                        MediaItemEntity(
                            remotePath = item.remotePath,
                            fileName = item.fileName,
                            mimeType = item.mimeType,
                            size = item.size,
                            lastModified = item.lastModified,
                            captureTimestamp = item.captureTimestamp ?: existing?.captureTimestamp,
                            etag = item.etag,
                            folderPath = item.folderPath,
                            thumbnailPath = if (preserveThumbnail) existing.thumbnailPath else null,
                            thumbnailStatus = if (preserveThumbnail) {
                                THUMBNAIL_STATUS_READY
                            } else {
                                THUMBNAIL_STATUS_PENDING
                            },
                            thumbnailRetryCount = 0,
                            thumbnailLastError = null,
                        )
                    }

                    mediaDao.upsertAll(entities)
                    totalSynced += entities.size
                    val upsertDurationMs = elapsedSince(upsertStartMs)
                    logPerf(
                        "library_sync_folder_success " +
                            "index=${index + 1}/${selectedFolders.size} " +
                            "items=${entities.size} " +
                            "listMs=$listDurationMs " +
                            "upsertMs=$upsertDurationMs"
                    )

                    // Update checkpoint
                    folderDao.upsertCheckpoint(
                        SyncCheckpointEntity(
                            folderPath = folder.remotePath,
                            lastSyncTimestamp = System.currentTimeMillis(),
                            lastEtag = "",
                            status = SyncState.Completed.name,
                        )
                    )
                }
                is WebDavClient.WebDavResult.Error -> {
                    failedFolders++
                    logPerf(
                        "library_sync_folder_error " +
                            "index=${index + 1}/${selectedFolders.size} " +
                            "listMs=$listDurationMs"
                    )
                    // Update checkpoint as failed
                    folderDao.upsertCheckpoint(
                        SyncCheckpointEntity(
                            folderPath = folder.remotePath,
                            lastSyncTimestamp = System.currentTimeMillis(),
                            lastEtag = checkpoint?.lastEtag.orEmpty(),
                            status = SyncState.Failed.name,
                        )
                    )
                }
            }
        }

        val totalDurationMs = elapsedSince(workerStartMs)
        val result = if (failedFolders > 0 && failedFolders < selectedFolders.size) {
            // Partial success — some folders failed
            Result.success(
                workDataOf(
                    KEY_SYNCED_COUNT to totalSynced,
                    KEY_STATUS to SyncState.Partial.name,
                )
            )
        } else if (failedFolders == selectedFolders.size) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    KEY_SYNCED_COUNT to totalSynced,
                    KEY_STATUS to SyncState.Completed.name,
                )
            )
        }

        logPerf(
            "library_sync_finish " +
                "status=${result.javaClass.simpleName} " +
                "synced=$totalSynced " +
                "failedFolders=$failedFolders " +
                "durationMs=$totalDurationMs"
        )
        return result
    }

    private fun elapsedSince(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    private fun logPerf(message: String) {
        Log.d(PERF_TAG, message)
    }

    companion object {
        const val WORK_NAME = "library_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_CURRENT_FOLDER = "current_folder"

        private const val PERF_TAG = "ImageNextPerf"
    }
}

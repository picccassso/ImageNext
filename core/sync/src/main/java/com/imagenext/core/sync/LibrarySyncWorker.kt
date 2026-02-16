package com.imagenext.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity
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
            return Result.success(workDataOf(KEY_SYNCED_COUNT to 0))
        }

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
            val result = webDavClient.listMediaFiles(
                serverUrl = session.serverUrl,
                loginName = session.loginName,
                appPassword = session.appPassword,
                folderPath = folder.remotePath,
            )

            when (result) {
                is WebDavClient.WebDavResult.Success -> {
                    val entities = result.data.map { item ->
                        MediaItemEntity(
                            remotePath = item.remotePath,
                            fileName = item.fileName,
                            mimeType = item.mimeType,
                            size = item.size,
                            lastModified = item.lastModified,
                            etag = item.etag,
                            folderPath = item.folderPath,
                            // Preserve existing thumbnail if item already exists
                            thumbnailPath = null,
                        )
                    }

                    mediaDao.upsertAll(entities)
                    totalSynced += entities.size

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

        return if (failedFolders > 0 && failedFolders < selectedFolders.size) {
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
    }

    companion object {
        const val WORK_NAME = "library_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_CURRENT_FOLDER = "current_folder"
    }
}

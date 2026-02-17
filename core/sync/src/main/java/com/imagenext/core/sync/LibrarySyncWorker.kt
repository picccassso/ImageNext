package com.imagenext.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_PENDING
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_READY
import com.imagenext.core.model.SyncState
import com.imagenext.core.network.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

        logPerf("library_sync_start folders=${selectedFolders.size} concurrency=$FOLDER_SCAN_CONCURRENCY")

        setProgress(
            workDataOf(
                KEY_PROGRESS_CURRENT to 0,
                KEY_PROGRESS_TOTAL to selectedFolders.size,
                KEY_CURRENT_FOLDER to "",
            )
        )

        val totalSynced = AtomicInteger(0)
        val failedFolders = AtomicInteger(0)
        val completedFolders = AtomicInteger(0)
        val thumbnailOverlapKickoffRequested = AtomicBoolean(false)
        val scanSemaphore = Semaphore(FOLDER_SCAN_CONCURRENCY)
        val shouldMergeExistingMetadata = mediaDao.getCount() > 0

        coroutineScope {
            selectedFolders.withIndex().forEach { (index, folder) ->
                launch(Dispatchers.IO) {
                    scanSemaphore.withPermit {
                        if (isStopped) return@withPermit

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
                                val existingByPath = if (shouldMergeExistingMetadata) {
                                    loadExistingByPathMap(
                                        mediaDao = mediaDao,
                                        remotePaths = result.data.map { it.remotePath },
                                    )
                                } else {
                                    emptyMap()
                                }

                                val entities = result.data.map { item ->
                                    val existing = existingByPath[item.remotePath]
                                    val mergedCaptureTimestamp = item.captureTimestamp ?: existing?.captureTimestamp
                                    val timelineSortKey = mergedCaptureTimestamp ?: item.lastModified
                                    val metadataUnchanged = existing != null &&
                                        existing.etag == item.etag &&
                                        existing.size == item.size &&
                                        existing.lastModified == item.lastModified &&
                                        existing.captureTimestamp == mergedCaptureTimestamp
                                    val preserveThumbnail = metadataUnchanged && !existing.thumbnailPath.isNullOrBlank()

                                    MediaItemEntity(
                                        remotePath = item.remotePath,
                                        fileName = item.fileName,
                                        mimeType = item.mimeType,
                                        size = item.size,
                                        lastModified = item.lastModified,
                                        captureTimestamp = mergedCaptureTimestamp,
                                        timelineSortKey = timelineSortKey,
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
                                totalSynced.addAndGet(entities.size)
                                val upsertDurationMs = elapsedSince(upsertStartMs)
                                logPerf(
                                    "library_sync_folder_success " +
                                        "index=${index + 1}/${selectedFolders.size} " +
                                        "items=${entities.size} " +
                                        "listMs=$listDurationMs " +
                                        "upsertMs=$upsertDurationMs"
                                )

                                val needsThumbnailBackfill = entities.any { entity ->
                                    entity.thumbnailStatus == THUMBNAIL_STATUS_PENDING
                                }
                                if (
                                    needsThumbnailBackfill &&
                                    thumbnailOverlapKickoffRequested.compareAndSet(false, true)
                                ) {
                                    ThumbnailWorker.enqueueBackfill(applicationContext)
                                    logPerf("library_sync_thumbnail_overlap_kickoff folderIndex=${index + 1}")
                                }

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
                                failedFolders.incrementAndGet()
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

                        val completed = completedFolders.incrementAndGet()
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to completed,
                                KEY_PROGRESS_TOTAL to selectedFolders.size,
                                KEY_CURRENT_FOLDER to folder.displayName,
                            )
                        )
                    }
                }
            }
        }

        val totalSyncedCount = totalSynced.get()
        val failedFoldersCount = failedFolders.get()

        if (!isStopped && totalSyncedCount > 0 && !thumbnailOverlapKickoffRequested.get()) {
            ThumbnailWorker.enqueueBackfill(applicationContext)
        }

        val totalDurationMs = elapsedSince(workerStartMs)
        val result = if (failedFoldersCount > 0 && failedFoldersCount < selectedFolders.size) {
            // Partial success — some folders failed
            Result.success(
                workDataOf(
                    KEY_SYNCED_COUNT to totalSyncedCount,
                    KEY_STATUS to SyncState.Partial.name,
                )
            )
        } else if (failedFoldersCount == selectedFolders.size) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    KEY_SYNCED_COUNT to totalSyncedCount,
                    KEY_STATUS to SyncState.Completed.name,
                )
            )
        }

        logPerf(
            "library_sync_finish " +
                "status=${result.javaClass.simpleName} " +
                "synced=$totalSyncedCount " +
                "failedFolders=$failedFoldersCount " +
                "durationMs=$totalDurationMs"
        )
        return result
    }

    private suspend fun loadExistingByPathMap(
        mediaDao: MediaDao,
        remotePaths: List<String>,
    ): Map<String, MediaItemEntity> {
        if (remotePaths.isEmpty()) return emptyMap()

        val existingItems = mutableListOf<MediaItemEntity>()
        remotePaths
            .chunked(REMOTE_PATH_LOOKUP_CHUNK_SIZE)
            .forEach { chunk ->
                existingItems += mediaDao.getByRemotePaths(chunk)
            }
        return existingItems.associateBy { it.remotePath }
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
        private const val REMOTE_PATH_LOOKUP_CHUNK_SIZE = 400
        private const val FOLDER_SCAN_CONCURRENCY = 2
    }
}

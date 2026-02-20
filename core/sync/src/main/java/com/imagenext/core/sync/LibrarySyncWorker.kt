package com.imagenext.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.dao.MediaPruneRef
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
import java.io.File
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
        val albumDao = database.albumDao()
        val cacheRootDir = applicationContext.cacheDir

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
        val totalPruned = AtomicInteger(0)
        val totalAlbumRefsPruned = AtomicInteger(0)
        val totalThumbnailsDeleted = AtomicInteger(0)
        val missingFolderPurgedCount = AtomicInteger(0)
        val headCheckedMissingPurgedCount = AtomicInteger(0)
        val failedFolders = AtomicInteger(0)
        val transientFailedFolders = AtomicInteger(0)
        val terminalFailedFolders = AtomicInteger(0)
        val completedFolders = AtomicInteger(0)
        val thumbnailOverlapKickoffRequested = AtomicBoolean(false)
        val scanSemaphore = Semaphore(FOLDER_SCAN_CONCURRENCY)
        val shouldMergeExistingMetadata = mediaDao.getCount() > 0

        coroutineScope {
            selectedFolders.withIndex().forEach { (index, folder) ->
                launch(Dispatchers.IO) {
                    scanSemaphore.withPermit {
                        if (isStopped) return@withPermit

                        val checkpoint = folderDao.getCheckpoint(folder.remotePath)

                        val listStartMs = SystemClock.elapsedRealtime()
                        val recursiveResult = webDavClient.listMediaFilesRecursively(
                            serverUrl = session.serverUrl,
                            loginName = session.loginName,
                            appPassword = session.appPassword,
                            folderPath = folder.remotePath,
                        )
                        val usedFallback = recursiveResult is WebDavClient.WebDavResult.Error

                        val result: WebDavClient.WebDavResult<WebDavClient.RecursiveMediaScan> =
                            when (recursiveResult) {
                                is WebDavClient.WebDavResult.Success -> recursiveResult
                                is WebDavClient.WebDavResult.Error -> {
                                    // Fall back to shallow listing if recursive traversal fails.
                                    when (
                                        val fallback = webDavClient.listMediaFiles(
                                            serverUrl = session.serverUrl,
                                            loginName = session.loginName,
                                            appPassword = session.appPassword,
                                            folderPath = folder.remotePath,
                                        )
                                    ) {
                                        is WebDavClient.WebDavResult.Success -> {
                                            WebDavClient.WebDavResult.Success(
                                                WebDavClient.RecursiveMediaScan(
                                                    mediaItems = fallback.data,
                                                    scannedFolders = setOf(normalizeRemotePath(folder.remotePath)),
                                                )
                                            )
                                        }

                                        is WebDavClient.WebDavResult.Error -> fallback
                                    }
                                }
                            }

                        val listDurationMs = elapsedSince(listStartMs)

                        when (result) {
                            is WebDavClient.WebDavResult.Success -> {
                                val upsertStartMs = SystemClock.elapsedRealtime()
                                val scan = result.data
                                val existingByPath = if (shouldMergeExistingMetadata) {
                                    loadExistingByPathMap(
                                        mediaDao = mediaDao,
                                        remotePaths = scan.mediaItems.map { it.remotePath },
                                    )
                                } else {
                                    emptyMap()
                                }

                                val entities = scan.mediaItems.map { item ->
                                    val existing = existingByPath[item.remotePath]
                                    val mergedCaptureTimestamp = item.captureTimestamp ?: existing?.captureTimestamp
                                    val timelineSortKey = mergedCaptureTimestamp ?: item.lastModified
                                    val metadataUnchanged = existing != null &&
                                        existing.etag == item.etag &&
                                        existing.size == item.size &&
                                        existing.lastModified == item.lastModified &&
                                        existing.captureTimestamp == mergedCaptureTimestamp &&
                                        existing.fileId == item.fileId
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
                                        fileId = item.fileId,
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

                                if (entities.isNotEmpty()) {
                                    mediaDao.upsertAll(entities)
                                }
                                // Purge legacy AppleDouble sidecar rows from prior syncs.
                                mediaDao.deleteAppleDoubleByFolder(folder.remotePath)

                                val pruneStats = pruneStaleMediaForScannedFolders(
                                    mediaDao = mediaDao,
                                    albumDao = albumDao,
                                    cacheRootDir = cacheRootDir,
                                    scannedFolders = scan.scannedFolders,
                                    incomingRemotePathsByFolder = incomingRemotePathsByFolder(entities),
                                )

                                totalSynced.addAndGet(entities.size)
                                totalPruned.addAndGet(pruneStats.prunedCount)
                                totalAlbumRefsPruned.addAndGet(pruneStats.albumRefsPrunedCount)
                                totalThumbnailsDeleted.addAndGet(pruneStats.thumbnailsDeletedCount)

                                val upsertDurationMs = elapsedSince(upsertStartMs)
                                logPerf(
                                    "library_sync_folder_success " +
                                        "index=${index + 1}/${selectedFolders.size} " +
                                        "items=${entities.size} " +
                                        "prunedCount=${pruneStats.prunedCount} " +
                                        "albumRefsPrunedCount=${pruneStats.albumRefsPrunedCount} " +
                                        "thumbnailsDeletedCount=${pruneStats.thumbnailsDeletedCount} " +
                                        "fallback=$usedFallback " +
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

                                folderDao.upsertCheckpoint(
                                    SyncCheckpointEntity(
                                        folderPath = folder.remotePath,
                                        lastSyncTimestamp = System.currentTimeMillis(),
                                        lastEtag = "",
                                        status = SyncState.Completed.name,
                                        lastErrorCode = null,
                                        lastErrorMessage = null,
                                    )
                                )
                            }

                            is WebDavClient.WebDavResult.Error -> {
                                if (shouldPurgeMissingSelectedFolder(result)) {
                                    val purgeStats = purgeMissingSelectedFolder(
                                        mediaDao = mediaDao,
                                        albumDao = albumDao,
                                        cacheRootDir = cacheRootDir,
                                        selectedFolderPath = folder.remotePath,
                                    )
                                    totalPruned.addAndGet(purgeStats.prunedCount)
                                    totalAlbumRefsPruned.addAndGet(purgeStats.albumRefsPrunedCount)
                                    totalThumbnailsDeleted.addAndGet(purgeStats.thumbnailsDeletedCount)
                                    missingFolderPurgedCount.addAndGet(purgeStats.prunedCount)

                                    logPerf(
                                        "library_sync_folder_missing_purged " +
                                            "index=${index + 1}/${selectedFolders.size} " +
                                            "purgedCount=${purgeStats.prunedCount} " +
                                            "albumRefsPrunedCount=${purgeStats.albumRefsPrunedCount} " +
                                            "thumbnailsDeletedCount=${purgeStats.thumbnailsDeletedCount} " +
                                            "fallback=$usedFallback " +
                                            "listMs=$listDurationMs"
                                    )

                                    folderDao.upsertCheckpoint(
                                        SyncCheckpointEntity(
                                            folderPath = folder.remotePath,
                                            lastSyncTimestamp = System.currentTimeMillis(),
                                            lastEtag = "",
                                            status = SyncState.Completed.name,
                                            lastErrorCode = null,
                                            lastErrorMessage = null,
                                        )
                                    )
                                } else {
                                    failedFolders.incrementAndGet()
                                    if (result.isTransient) {
                                        transientFailedFolders.incrementAndGet()
                                    } else {
                                        terminalFailedFolders.incrementAndGet()
                                    }
                                    val failureCode = folderFailureCode(result)
                                    logPerf(
                                        "library_sync_folder_error " +
                                            "index=${index + 1}/${selectedFolders.size} " +
                                            "listMs=$listDurationMs " +
                                            "transient=${result.isTransient} " +
                                            "errorCode=$failureCode"
                                    )
                                    folderDao.upsertCheckpoint(
                                        SyncCheckpointEntity(
                                            folderPath = folder.remotePath,
                                            lastSyncTimestamp = System.currentTimeMillis(),
                                            lastEtag = checkpoint?.lastEtag.orEmpty(),
                                            status = SyncState.Failed.name,
                                            lastErrorCode = failureCode,
                                            lastErrorMessage = result.message,
                                        )
                                    )
                                }
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
        val totalPrunedCount = totalPruned.get()
        val totalAlbumRefsPrunedCount = totalAlbumRefsPruned.get()
        val totalThumbnailsDeletedCount = totalThumbnailsDeleted.get()
        val missingFolderPurgedTotal = missingFolderPurgedCount.get()
        val failedFoldersCount = failedFolders.get()
        val transientFailedFoldersCount = transientFailedFolders.get()
        val terminalFailedFoldersCount = terminalFailedFolders.get()

        if (!isStopped) {
            val headProbeStats = purgeMissingMediaByHeadProbe(
                mediaDao = mediaDao,
                albumDao = albumDao,
                webDavClient = webDavClient,
                serverUrl = session.serverUrl,
                loginName = session.loginName,
                appPassword = session.appPassword,
                cacheRootDir = cacheRootDir,
            )
            totalPruned.addAndGet(headProbeStats.prunedCount)
            totalAlbumRefsPruned.addAndGet(headProbeStats.albumRefsPrunedCount)
            totalThumbnailsDeleted.addAndGet(headProbeStats.thumbnailsDeletedCount)
            headCheckedMissingPurgedCount.addAndGet(headProbeStats.prunedCount)
            if (headProbeStats.prunedCount > 0) {
                logPerf(
                    "library_sync_head_probe_pruned " +
                        "prunedCount=${headProbeStats.prunedCount} " +
                        "albumRefsPrunedCount=${headProbeStats.albumRefsPrunedCount} " +
                        "thumbnailsDeletedCount=${headProbeStats.thumbnailsDeletedCount}"
                )
            }
        }

        if (!isStopped && totalSyncedCount > 0 && !thumbnailOverlapKickoffRequested.get()) {
            ThumbnailWorker.enqueueBackfill(applicationContext)
        }

        val totalDurationMs = elapsedSince(workerStartMs)
        val headCheckedMissingPurgedTotal = headCheckedMissingPurgedCount.get()
        val allFoldersFailed = failedFoldersCount == selectedFolders.size
        val allFailuresTransient = allFoldersFailed &&
            transientFailedFoldersCount == failedFoldersCount &&
            terminalFailedFoldersCount == 0
        val shouldRetryAllFolderTransientFailure = allFailuresTransient &&
            runAttemptCount < MAX_TRANSIENT_ALL_FOLDER_RETRY_ATTEMPTS

        val result = when {
            failedFoldersCount > 0 && failedFoldersCount < selectedFolders.size -> {
                Result.success(
                    workDataOf(
                        KEY_SYNCED_COUNT to totalSyncedCount,
                        KEY_STATUS to SyncState.Partial.name,
                    )
                )
            }

            allFoldersFailed && shouldRetryAllFolderTransientFailure -> {
                Result.retry()
            }

            allFoldersFailed -> {
                val errorCategory = when {
                    allFailuresTransient -> ERROR_CATEGORY_TRANSIENT
                    terminalFailedFoldersCount == failedFoldersCount -> ERROR_CATEGORY_TERMINAL
                    else -> ERROR_CATEGORY_MIXED
                }
                val errorCode = when (errorCategory) {
                    ERROR_CATEGORY_TRANSIENT -> ERROR_ALL_FOLDERS_TRANSIENT_EXHAUSTED
                    ERROR_CATEGORY_TERMINAL -> ERROR_ALL_FOLDERS_TERMINAL
                    else -> ERROR_ALL_FOLDERS_MIXED
                }

                Result.failure(
                    workDataOf(
                        KEY_SYNCED_COUNT to totalSyncedCount,
                        KEY_STATUS to SyncState.Failed.name,
                        KEY_ERROR to errorCode,
                        KEY_ERROR_CATEGORY to errorCategory,
                    )
                )
            }

            else -> {
                Result.success(
                    workDataOf(
                        KEY_SYNCED_COUNT to totalSyncedCount,
                        KEY_STATUS to SyncState.Completed.name,
                    )
                )
            }
        }

        logPerf(
            "library_sync_finish " +
                "status=${result.javaClass.simpleName} " +
                "synced=$totalSyncedCount " +
                "prunedCount=$totalPrunedCount " +
                "albumRefsPrunedCount=$totalAlbumRefsPrunedCount " +
                "thumbnailsDeletedCount=$totalThumbnailsDeletedCount " +
                "missingFolderPurgedCount=$missingFolderPurgedTotal " +
                "headCheckedMissingPurgedCount=$headCheckedMissingPurgedTotal " +
                "failedFolders=$failedFoldersCount " +
                "transientFailedFolders=$transientFailedFoldersCount " +
                "terminalFailedFolders=$terminalFailedFoldersCount " +
                "runAttemptCount=$runAttemptCount " +
                "durationMs=$totalDurationMs"
        )
        return result
    }

    private suspend fun pruneStaleMediaForScannedFolders(
        mediaDao: MediaDao,
        albumDao: AlbumDao,
        cacheRootDir: File,
        scannedFolders: Set<String>,
        incomingRemotePathsByFolder: Map<String, Set<String>>,
    ): ReconcileCleanupStats {
        if (scannedFolders.isEmpty()) return ReconcileCleanupStats()

        val existingRefsByFolder = scannedFolders
            .map(::normalizeRemotePath)
            .distinct()
            .associateWith { scannedFolder ->
                mediaDao.getMediaRefsByFolderPaths(folderPathAliases(scannedFolder).toList())
            }
        val staleRefs = computeStaleMediaRefsByFolder(
            scannedFolders = scannedFolders,
            existingRefsByFolder = existingRefsByFolder,
            incomingRemotePathsByFolder = incomingRemotePathsByFolder,
        )

        return pruneMediaRefs(
            mediaDao = mediaDao,
            albumDao = albumDao,
            cacheRootDir = cacheRootDir,
            refs = staleRefs,
        )
    }

    private suspend fun purgeMissingSelectedFolder(
        mediaDao: MediaDao,
        albumDao: AlbumDao,
        cacheRootDir: File,
        selectedFolderPath: String,
    ): ReconcileCleanupStats {
        val query = buildMissingFolderPurgeQuery(selectedFolderPath)
        val refs = mediaDao.getMediaRefsUnderRemotePath(
            rootPath = query.rootPath,
            rootPrefixLike = query.rootPrefixLike,
        )
        return pruneMediaRefs(
            mediaDao = mediaDao,
            albumDao = albumDao,
            cacheRootDir = cacheRootDir,
            refs = refs.distinctBy { it.remotePath },
        )
    }

    private suspend fun purgeMissingMediaByHeadProbe(
        mediaDao: MediaDao,
        albumDao: AlbumDao,
        webDavClient: WebDavClient,
        serverUrl: String,
        loginName: String,
        appPassword: String,
        cacheRootDir: File,
    ): ReconcileCleanupStats {
        val failedOrSkippedCandidates = mediaDao.getMissingRemoteProbeCandidates(HEAD_PROBE_FAILED_LIMIT)
        val readyCandidates = mediaDao.getReadyThumbnailProbeCandidates(HEAD_PROBE_READY_LIMIT)
            .filter { ref ->
                !isThumbnailFilePresentInCache(
                    thumbnailPath = ref.thumbnailPath,
                    cacheRootDir = cacheRootDir,
                )
            }
        val candidates = (failedOrSkippedCandidates + readyCandidates)
            .distinctBy { it.remotePath }
            .take(HEAD_PROBE_MAX_PER_SYNC)
        if (candidates.isEmpty()) return ReconcileCleanupStats()

        val missingRefs = mutableListOf<MediaPruneRef>()
        candidates.forEach { candidate ->
            when (
                val head = webDavClient.headFile(
                    serverUrl = serverUrl,
                    loginName = loginName,
                    appPassword = appPassword,
                    remotePath = candidate.remotePath,
                )
            ) {
                is WebDavClient.WebDavResult.Success -> {
                    if (head.data == null) {
                        missingRefs += candidate
                    }
                }
                is WebDavClient.WebDavResult.Error -> Unit
            }
        }
        return pruneMediaRefs(
            mediaDao = mediaDao,
            albumDao = albumDao,
            cacheRootDir = cacheRootDir,
            refs = missingRefs,
        )
    }

    private suspend fun pruneMediaRefs(
        mediaDao: MediaDao,
        albumDao: AlbumDao,
        cacheRootDir: File,
        refs: List<MediaPruneRef>,
    ): ReconcileCleanupStats {
        if (refs.isEmpty()) return ReconcileCleanupStats()

        val remotePaths = refs.map { it.remotePath }.distinct()
        var albumRefsPrunedCount = 0
        remotePaths
            .chunked(PRUNE_BATCH_SIZE)
            .forEach { chunk ->
                albumRefsPrunedCount += albumDao.deleteMediaRefs(chunk)
                mediaDao.deleteByRemotePaths(chunk)
            }
        val thumbnailsDeletedCount = deleteThumbnailFilesUnderCache(
            thumbnailPaths = refs.mapNotNull { it.thumbnailPath }.distinct(),
            cacheRootDir = cacheRootDir,
        )
        return ReconcileCleanupStats(
            prunedCount = remotePaths.size,
            albumRefsPrunedCount = albumRefsPrunedCount,
            thumbnailsDeletedCount = thumbnailsDeletedCount,
        )
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

    private fun folderFailureCode(error: WebDavClient.WebDavResult.Error): String {
        val category = when (error.category) {
            WebDavClient.WebDavResult.ErrorCategory.TRANSIENT -> "transient"
            WebDavClient.WebDavResult.ErrorCategory.AUTH -> "auth"
            WebDavClient.WebDavResult.ErrorCategory.NOT_FOUND -> "not_found"
            WebDavClient.WebDavResult.ErrorCategory.SECURITY -> "security"
            WebDavClient.WebDavResult.ErrorCategory.CLIENT -> "client"
            WebDavClient.WebDavResult.ErrorCategory.UNKNOWN -> "unknown"
        }
        return error.httpStatusCode?.let { statusCode ->
            "${category}_http_$statusCode"
        } ?: category
    }

    companion object {
        const val WORK_NAME = "library_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_CATEGORY = "error_category"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_CURRENT_FOLDER = "current_folder"

        private const val PERF_TAG = "ImageNextPerf"
        private const val REMOTE_PATH_LOOKUP_CHUNK_SIZE = 400
        private const val PRUNE_BATCH_SIZE = 350
        private const val HEAD_PROBE_FAILED_LIMIT = 160
        private const val HEAD_PROBE_READY_LIMIT = 160
        private const val HEAD_PROBE_MAX_PER_SYNC = 200
        private const val FOLDER_SCAN_CONCURRENCY = 4
        private const val MAX_TRANSIENT_ALL_FOLDER_RETRY_ATTEMPTS = 3

        private const val ERROR_CATEGORY_TRANSIENT = "transient"
        private const val ERROR_CATEGORY_TERMINAL = "terminal"
        private const val ERROR_CATEGORY_MIXED = "mixed"

        private const val ERROR_ALL_FOLDERS_TRANSIENT_EXHAUSTED = "all_folders_transient_failed_exhausted"
        private const val ERROR_ALL_FOLDERS_TERMINAL = "all_folders_terminal_failed"
        private const val ERROR_ALL_FOLDERS_MIXED = "all_folders_mixed_failed"
    }
}

internal data class ReconcileCleanupStats(
    val prunedCount: Int = 0,
    val albumRefsPrunedCount: Int = 0,
    val thumbnailsDeletedCount: Int = 0,
)

internal data class MissingFolderPurgeQuery(
    val rootPath: String,
    val rootPrefixLike: String,
)

internal fun incomingRemotePathsByFolder(entities: List<MediaItemEntity>): Map<String, Set<String>> {
    return entities
        .groupBy { normalizeRemotePath(it.folderPath) }
        .mapValues { (_, rows) -> rows.map { it.remotePath }.toSet() }
}

internal fun folderPathAliases(folderPath: String): Set<String> {
    val normalized = normalizeRemotePath(folderPath)
    return if (normalized == "/") {
        setOf("/")
    } else {
        setOf(normalized, "$normalized/")
    }
}

internal fun computeStaleMediaRefs(
    existingRefs: List<MediaPruneRef>,
    incomingRemotePaths: Set<String>,
): List<MediaPruneRef> {
    if (existingRefs.isEmpty()) return emptyList()
    if (incomingRemotePaths.isEmpty()) return existingRefs
    return existingRefs.filter { ref -> ref.remotePath !in incomingRemotePaths }
}

internal fun computeStaleMediaRefsByFolder(
    scannedFolders: Set<String>,
    existingRefsByFolder: Map<String, List<MediaPruneRef>>,
    incomingRemotePathsByFolder: Map<String, Set<String>>,
): List<MediaPruneRef> {
    if (scannedFolders.isEmpty()) return emptyList()
    return scannedFolders
        .map(::normalizeRemotePath)
        .distinct()
        .flatMap { scannedFolder ->
            computeStaleMediaRefs(
                existingRefs = existingRefsByFolder[scannedFolder].orEmpty(),
                incomingRemotePaths = incomingRemotePathsByFolder[scannedFolder].orEmpty(),
            )
        }
        .distinctBy { it.remotePath }
}

internal fun buildMissingFolderPurgeQuery(folderPath: String): MissingFolderPurgeQuery {
    val normalized = normalizeRemotePath(folderPath)
    return MissingFolderPurgeQuery(
        rootPath = normalized,
        rootPrefixLike = if (normalized == "/") "/%" else "$normalized/%",
    )
}

internal fun shouldPurgeMissingSelectedFolder(error: WebDavClient.WebDavResult.Error): Boolean {
    return error.category == WebDavClient.WebDavResult.ErrorCategory.NOT_FOUND &&
        error.httpStatusCode == 404
}

internal fun deleteThumbnailFilesUnderCache(
    thumbnailPaths: Collection<String>,
    cacheRootDir: File,
): Int {
    if (thumbnailPaths.isEmpty()) return 0
    val rootCanonical = try {
        cacheRootDir.canonicalFile
    } catch (_: Exception) {
        return 0
    }
    val rootPath = rootCanonical.path
    var deleted = 0
    thumbnailPaths.forEach { thumbnailPath ->
        if (thumbnailPath.isBlank()) return@forEach
        val candidate = try {
            File(thumbnailPath).canonicalFile
        } catch (_: Exception) {
            return@forEach
        }
        val candidatePath = candidate.path
        val withinCache = candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
        if (!withinCache) return@forEach
        if (candidate.exists() && candidate.isFile && candidate.delete()) {
            deleted++
        }
    }
    return deleted
}

internal fun isThumbnailFilePresentInCache(
    thumbnailPath: String?,
    cacheRootDir: File,
): Boolean {
    if (thumbnailPath.isNullOrBlank()) return false
    val rootCanonical = try {
        cacheRootDir.canonicalFile
    } catch (_: Exception) {
        return false
    }
    val candidate = try {
        File(thumbnailPath).canonicalFile
    } catch (_: Exception) {
        return false
    }
    val rootPath = rootCanonical.path
    val candidatePath = candidate.path
    val withinCache = candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    if (!withinCache) return false
    return candidate.exists() && candidate.isFile
}

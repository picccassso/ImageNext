package com.imagenext.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.data.BackupPolicyRepository
import com.imagenext.core.data.LocalMediaDetector
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.UploadQueueEntity
import com.imagenext.core.model.BackupDeletePolicy
import com.imagenext.core.model.BackupSourceScope
import com.imagenext.core.model.BackupSyncMode
import com.imagenext.core.model.UploadOperation
import com.imagenext.core.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Detects local media changes and enqueues backup queue items with debounce.
 */
class LocalMediaDetectorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val policy = BackupPolicyRepository(applicationContext).getPolicy()
        val manualTrigger = inputData.getBoolean(KEY_MANUAL_TRIGGER, false)

        if (!policy.enabled) return Result.success()
        if (!policy.backupRootSelectedByUser) {
            return Result.success(workDataOf(KEY_REASON to "backup_root_not_selected"))
        }
        if (policy.syncMode == BackupSyncMode.MANUAL_ONLY && !manualTrigger) {
            return Result.success(workDataOf(KEY_REASON to "manual_mode"))
        }
        if (!policy.autoUploadNewMedia && !manualTrigger) {
            return Result.success(workDataOf(KEY_REASON to "auto_upload_disabled"))
        }

        val database = AppDatabase.getInstance(applicationContext)
        val queueDao = database.uploadQueueDao()
        val registryDao = database.uploadedMediaRegistryDao()
        val folderDao = database.localBackupFolderDao()
        val detector = LocalMediaDetector(applicationContext)

        val now = System.currentTimeMillis()
        val detectedRows = when (policy.sourceScope) {
            BackupSourceScope.FULL_LIBRARY -> {
                if (!detector.hasReadPermission()) {
                    return Result.success(workDataOf(KEY_REASON to "missing_media_permission"))
                }
                detector.scanAllMedia(policy.mediaTypes)
            }

            BackupSourceScope.SELECTED_FOLDERS -> {
                val selectedFolders = withContext(Dispatchers.IO) { folderDao.getAllList() }
                if (selectedFolders.isEmpty()) {
                    return Result.success(workDataOf(KEY_REASON to "no_selected_local_folders"))
                }
                try {
                    detector.scanTreeUris(
                        treeUris = selectedFolders.map { it.treeUri },
                        mediaTypes = policy.mediaTypes,
                    )
                } catch (_: SecurityException) {
                    return Result.success(workDataOf(KEY_REASON to "folder_permission_revoked"))
                }
            }
        }

        var insertedCount = 0
        withContext(Dispatchers.IO) {
            val queueRows = detectedRows.map { media ->
                UploadQueueEntity(
                    stableKey = media.stableKey,
                    operation = UploadOperation.UPLOAD.name,
                    localUri = media.contentUri,
                    mimeType = media.mimeType,
                    size = media.size,
                    dateTaken = media.dateTaken,
                    targetRemoteFolder = resolveTargetRemoteFolder(
                        backupRoot = policy.backupRoot,
                        uploadStructure = policy.uploadStructure,
                        captureTimestampMs = media.dateTaken,
                    ),
                    targetFileName = media.fileName,
                    status = UploadStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                    nextAttemptAt = 0,
                    remotePath = null,
                )
            }
            if (queueRows.isNotEmpty()) {
                insertedCount = queueDao.insertAll(queueRows).count { it != -1L }
            }

            detectedRows.forEach { media ->
                registryDao.markSeen(
                    stableKey = media.stableKey,
                    seenAt = now,
                    localUri = media.contentUri,
                )
            }
        }

        var queuedDeleteCount = 0
        if (policy.deletePolicy == BackupDeletePolicy.MIRROR_DELETE) {
            val currentStableKeys = detectedRows.map { it.stableKey }.toSet()
            withContext(Dispatchers.IO) {
                val activeRegistryRows = registryDao.getActiveRows()
                activeRegistryRows
                    .asSequence()
                    .filter { it.stableKey !in currentStableKeys }
                    .forEach { uploaded ->
                        val queued = queueDao.insert(
                            UploadQueueEntity(
                                stableKey = uploaded.stableKey,
                                operation = UploadOperation.DELETE.name,
                                localUri = null,
                                mimeType = null,
                                size = uploaded.size,
                                dateTaken = uploaded.dateTaken,
                                targetRemoteFolder = parentRemoteFolder(uploaded.remotePath),
                                targetFileName = remoteFileName(uploaded.remotePath),
                                status = UploadStatus.PENDING.name,
                                createdAt = now,
                                updatedAt = now,
                                nextAttemptAt = 0,
                                remotePath = uploaded.remotePath,
                            )
                        )
                        if (queued != -1L) queuedDeleteCount++
                    }
            }
        }

        withContext(Dispatchers.IO) {
            queueDao.pruneByStatusOlderThan(
                status = UploadStatus.DONE.name,
                cutoff = now - DONE_RETENTION_MS,
            )
        }

        val shouldKickUpload = insertedCount > 0 || queuedDeleteCount > 0 || manualTrigger
        if (shouldKickUpload) {
            MediaUploadWorker.enqueueDebounced(
                context = applicationContext,
                manualTrigger = manualTrigger,
            )
        }

        return Result.success(
            workDataOf(
                KEY_INSERTED_COUNT to insertedCount,
                KEY_DELETE_COUNT to queuedDeleteCount,
            )
        )
    }

    companion object {
        const val WORK_NAME = "local_media_detector"
        const val PERIODIC_WORK_NAME = "local_media_detector_periodic"
        const val KEY_MANUAL_TRIGGER = "manual_trigger"
        const val KEY_INSERTED_COUNT = "inserted_count"
        const val KEY_DELETE_COUNT = "delete_count"
        const val KEY_REASON = "reason"

        private const val DONE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

        fun enqueue(
            context: Context,
            manualTrigger: Boolean,
            initialDelaySeconds: Long = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val request = OneTimeWorkRequestBuilder<LocalMediaDetectorWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MANUAL_TRIGGER to manualTrigger,
                    )
                )
                .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun schedulePeriodic(context: Context, intervalHours: Long = 1L) {
            val request = PeriodicWorkRequestBuilder<LocalMediaDetectorWorker>(
                intervalHours,
                TimeUnit.HOURS,
            )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }
    }
}

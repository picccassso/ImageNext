package com.imagenext.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.data.BackupPolicyRepository
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.UploadQueueEntity
import com.imagenext.core.database.entity.UploadedMediaRegistryEntity
import com.imagenext.core.model.BackupNetworkPolicy
import com.imagenext.core.model.BackupSyncMode
import com.imagenext.core.model.UploadOperation
import com.imagenext.core.model.UploadStatus
import com.imagenext.core.network.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Upload queue worker that performs WebDAV PUT/DELETE operations.
 */
class MediaUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(message = "Preparing backup uploads")
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val policyRepository = BackupPolicyRepository(applicationContext)
        val policy = policyRepository.getPolicy()
        val manualTrigger = inputData.getBoolean(KEY_MANUAL_TRIGGER, false)

        if (!policy.enabled) return Result.success()
        if (!policy.backupRootSelectedByUser) {
            return Result.success(
                workDataOf(
                    KEY_REASON to "backup_root_not_selected",
                    KEY_REPORTABLE_RUN to false,
                )
            )
        }
        if (policy.syncMode == BackupSyncMode.MANUAL_ONLY && !manualTrigger) {
            return Result.success(
                workDataOf(
                    KEY_REASON to "manual_mode",
                    KEY_REPORTABLE_RUN to false,
                )
            )
        }

        if (!policy.allowRoaming && isRoamingNetwork(applicationContext)) {
            return Result.retry()
        }

        val sessionRepo = SyncDependencies.getSessionRepository(applicationContext)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))
        val session = sessionRepo.getSession()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))

        val database = AppDatabase.getInstance(applicationContext)
        val queueDao = database.uploadQueueDao()
        val registryDao = database.uploadedMediaRegistryDao()
        val webDavClient = WebDavClient()

        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            // Recover rows left in UPLOADING state after process death/FGS crashes.
            queueDao.requeueAll(
                fromStatus = UploadStatus.UPLOADING.name,
                status = UploadStatus.PENDING.name,
                updatedAt = now,
            )
        }
        val totalPendingAtStart = withContext(Dispatchers.IO) {
            queueDao.getCountByStatus(UploadStatus.PENDING.name)
        }
        if (totalPendingAtStart <= 0) {
            return Result.success(
                workDataOf(
                    KEY_REASON to "empty_queue",
                    KEY_REPORTABLE_RUN to true,
                    KEY_SUCCESS_COUNT to 0,
                    KEY_FAILED_COUNT to 0,
                    KEY_PROCESSED_COUNT to 0,
                    KEY_UPLOADED_COUNT to 0,
                    KEY_SKIPPED_COUNT to 0,
                    KEY_DELETED_COUNT to 0,
                    KEY_FINISHED_AT to System.currentTimeMillis(),
                )
            )
        }

        var successCount = 0
        var failedCount = 0
        var processedCount = 0
        var uploadedCount = 0
        var skippedCount = 0
        var deletedCount = 0
        var lastError: String? = null
        var changedRemoteData = false
        var processedSinceStart = 0

        while (!isStopped) {
            val queue = withContext(Dispatchers.IO) {
                queueDao.getReadyByStatus(
                    status = UploadStatus.PENDING.name,
                    now = System.currentTimeMillis(),
                    limit = BATCH_LIMIT,
                )
            }
            if (queue.isEmpty()) break

            for ((index, item) in queue.withIndex()) {
                if (isStopped) break

                val attemptStart = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    queueDao.update(
                        item.copy(
                            status = UploadStatus.UPLOADING.name,
                            lastAttemptAt = attemptStart,
                            updatedAt = attemptStart,
                        )
                    )
                }

                val outcome = when (UploadOperation.valueOf(item.operation)) {
                    UploadOperation.UPLOAD -> processUpload(
                        item = item,
                        webDavClient = webDavClient,
                        serverUrl = session.serverUrl,
                        loginName = session.loginName,
                        appPassword = session.appPassword,
                    )
                    UploadOperation.DELETE -> processDelete(
                        item = item,
                        webDavClient = webDavClient,
                        serverUrl = session.serverUrl,
                        loginName = session.loginName,
                        appPassword = session.appPassword,
                    )
                }

                processedCount++
                processedSinceStart++
                when (outcome) {
                    is UploadOutcome.Success -> {
                        successCount++
                        when (outcome.kind) {
                            UploadSuccessKind.UPLOADED -> {
                                uploadedCount++
                                changedRemoteData = true
                            }
                            UploadSuccessKind.SKIPPED_EXISTING -> {
                                skippedCount++
                            }
                            UploadSuccessKind.DELETED -> {
                                deletedCount++
                                changedRemoteData = true
                            }
                        }
                        val updateTime = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            queueDao.update(
                                item.copy(
                                    status = UploadStatus.DONE.name,
                                    lastError = null,
                                    lastAttemptAt = updateTime,
                                    nextAttemptAt = 0,
                                    remotePath = outcome.remotePath,
                                    updatedAt = updateTime,
                                )
                            )

                            if (UploadOperation.valueOf(item.operation) == UploadOperation.UPLOAD) {
                                registryDao.upsert(
                                    UploadedMediaRegistryEntity(
                                        stableKey = item.stableKey,
                                        remotePath = outcome.remotePath,
                                        lastKnownLocalUri = item.localUri,
                                        bucketId = "unknown",
                                        size = item.size ?: 0L,
                                        dateTaken = item.dateTaken,
                                        sha256 = null,
                                        lastSeenAt = updateTime,
                                        uploadedAt = updateTime,
                                        deletedRemotelyAt = null,
                                    )
                                )
                            } else {
                                registryDao.markDeleted(
                                    stableKey = item.stableKey,
                                    deletedAt = updateTime,
                                )
                            }
                        }
                    }

                    is UploadOutcome.Failure -> {
                        failedCount++
                        lastError = outcome.errorCode
                        handleFailure(item = item, failure = outcome, queueDao = queueDao)
                    }
                }

                val progressCurrent = processedSinceStart.coerceAtMost(totalPendingAtStart)
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_CURRENT to progressCurrent,
                        KEY_PROGRESS_TOTAL to totalPendingAtStart,
                    )
                )
                val remaining = (totalPendingAtStart - processedSinceStart).coerceAtLeast(0)
                val message = if (totalPendingAtStart > queue.size || processedSinceStart > index + 1) {
                    "Uploading · $remaining remaining"
                } else {
                    "Uploading · ${index + 1} of ${queue.size}"
                }
                setForeground(
                    createForegroundInfo(
                        message = message,
                        progressCurrent = progressCurrent,
                        progressMax = totalPendingAtStart,
                    )
                )
            }
        }

        withContext(Dispatchers.IO) {
            queueDao.pruneByStatusOlderThan(
                status = UploadStatus.DONE.name,
                cutoff = System.currentTimeMillis() - DONE_RETENTION_MS,
            )
        }

        if (!isStopped && (uploadedCount > 0 || skippedCount > 0 || failedCount > 0)) {
            postCompletionNotification(uploadedCount, skippedCount, failedCount)
        }

        if (changedRemoteData && !isStopped) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                LibrarySyncWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .build(),
            )
        }

        return Result.success(
            workDataOf(
                KEY_REPORTABLE_RUN to true,
                KEY_SUCCESS_COUNT to successCount,
                KEY_FAILED_COUNT to failedCount,
                KEY_PROCESSED_COUNT to processedCount,
                KEY_UPLOADED_COUNT to uploadedCount,
                KEY_SKIPPED_COUNT to skippedCount,
                KEY_DELETED_COUNT to deletedCount,
                KEY_FINISHED_AT to System.currentTimeMillis(),
                KEY_ERROR to lastError,
            )
        )
    }

    private suspend fun processUpload(
        item: UploadQueueEntity,
        webDavClient: WebDavClient,
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): UploadOutcome {
        val targetFolder = normalizeRemotePath(item.targetRemoteFolder)
        val remotePath = item.remotePath ?: buildRemoteFilePath(targetFolder, item.targetFileName)

        when (
            val folderResult = webDavClient.ensureFolderPath(
                serverUrl = serverUrl,
                loginName = loginName,
                appPassword = appPassword,
                folderPath = targetFolder,
            )
        ) {
            is WebDavClient.WebDavResult.Error -> {
                return UploadOutcome.Failure(
                    transient = folderResult.isTransient,
                    errorCode = "ensure_folder_failed",
                )
            }
            is WebDavClient.WebDavResult.Success -> Unit
        }

        when (
            val headResult = webDavClient.headFile(
                serverUrl = serverUrl,
                loginName = loginName,
                appPassword = appPassword,
                remotePath = remotePath,
            )
        ) {
            is WebDavClient.WebDavResult.Error -> {
                return UploadOutcome.Failure(
                    transient = headResult.isTransient,
                    errorCode = "head_failed",
                )
            }

            is WebDavClient.WebDavResult.Success -> {
                val remoteInfo = headResult.data
                if (remoteInfo != null) {
                    val sameSize = remoteInfo.size != null && item.size != null && remoteInfo.size == item.size
                    return if (sameSize) {
                        UploadOutcome.Success(
                            remotePath = remotePath,
                            kind = UploadSuccessKind.SKIPPED_EXISTING,
                        )
                    } else {
                        UploadOutcome.Failure(
                            transient = false,
                            errorCode = ERROR_CONFLICT_SIZE_MISMATCH,
                        )
                    }
                }
            }
        }

        val localUri = item.localUri
            ?: return UploadOutcome.Failure(transient = false, errorCode = "missing_local_uri")

        val putResult = webDavClient.putFile(
            serverUrl = serverUrl,
            loginName = loginName,
            appPassword = appPassword,
            remotePath = remotePath,
            contentType = item.mimeType,
            contentLength = item.size,
            inputStreamProvider = {
                applicationContext.contentResolver.openInputStream(Uri.parse(localUri))
                    ?: throw IOException("Unable to open local content stream")
            },
        )

        return when (putResult) {
            is WebDavClient.WebDavResult.Success -> UploadOutcome.Success(
                remotePath = remotePath,
                kind = UploadSuccessKind.UPLOADED,
            )
            is WebDavClient.WebDavResult.Error -> UploadOutcome.Failure(
                transient = putResult.isTransient,
                errorCode = "upload_failed",
            )
        }
    }

    private fun processDelete(
        item: UploadQueueEntity,
        webDavClient: WebDavClient,
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): UploadOutcome {
        val remotePath = item.remotePath ?: buildRemoteFilePath(item.targetRemoteFolder, item.targetFileName)
        return when (
            val deleteResult = webDavClient.deleteFile(
                serverUrl = serverUrl,
                loginName = loginName,
                appPassword = appPassword,
                remotePath = remotePath,
            )
        ) {
            is WebDavClient.WebDavResult.Success -> UploadOutcome.Success(
                remotePath = remotePath,
                kind = UploadSuccessKind.DELETED,
            )
            is WebDavClient.WebDavResult.Error -> UploadOutcome.Failure(
                transient = deleteResult.isTransient,
                errorCode = "delete_failed",
            )
        }
    }

    private suspend fun handleFailure(
        item: UploadQueueEntity,
        failure: UploadOutcome.Failure,
        queueDao: com.imagenext.core.database.dao.UploadQueueDao,
    ) {
        val attempt = item.retryCount + 1
        val now = System.currentTimeMillis()
        val shouldRetry = failure.transient && attempt < MAX_RETRY_ATTEMPTS

        withContext(Dispatchers.IO) {
            queueDao.update(
                item.copy(
                    status = if (shouldRetry) UploadStatus.PENDING.name else UploadStatus.FAILED.name,
                    retryCount = attempt,
                    lastError = failure.errorCode,
                    lastAttemptAt = now,
                    nextAttemptAt = if (shouldRetry) now + computeRetryDelayMillis(attempt) else 0,
                    updatedAt = now,
                )
            )
        }
    }

    private fun postCompletionNotification(uploadedCount: Int, skippedCount: Int, failedCount: Int) {
        ensureNotificationChannel(applicationContext)
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager ?: return
        val allFailed = failedCount > 0 && uploadedCount == 0 && skippedCount == 0
        val title = if (allFailed) "Backup · $failedCount failed" else "Backup complete"
        val parts = buildList {
            if (uploadedCount > 0) add("$uploadedCount uploaded")
            if (skippedCount > 0) add("$skippedCount already backed up")
            if (failedCount > 0) add("$failedCount failed")
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(
                if (allFailed) android.R.drawable.stat_notify_error
                else android.R.drawable.stat_sys_upload_done,
            )
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        manager.notify(NOTIFICATION_ID_SUMMARY, notification)
    }

    private fun createForegroundInfo(
        message: String,
        progressCurrent: Int = 0,
        progressMax: Int = 0,
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ImageNext backup")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressMax > 0) {
            builder.setProgress(progressMax, progressCurrent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun isRoamingNetwork(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!isCellular) return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }

    companion object {
        const val WORK_NAME = "media_upload"
        const val KEY_MANUAL_TRIGGER = "manual_trigger"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_SUCCESS_COUNT = "success_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_PROCESSED_COUNT = "processed_count"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_SKIPPED_COUNT = "skipped_count"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_FINISHED_AT = "finished_at"
        const val KEY_REPORTABLE_RUN = "reportable_run"
        const val KEY_ERROR = "error"
        const val KEY_REASON = "reason"

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BATCH_LIMIT = 16
        private const val ERROR_CONFLICT_SIZE_MISMATCH = "conflict_size_mismatch"
        private const val DONE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private const val NOTIFICATION_CHANNEL_ID = "imagenext_backup_uploads"
        private const val NOTIFICATION_CHANNEL_NAME = "ImageNext Backup Uploads"
        private const val NOTIFICATION_ID = 4041
        private const val NOTIFICATION_ID_SUMMARY = 4042

        fun enqueueDebounced(
            context: Context,
            manualTrigger: Boolean = false,
            debounceSeconds: Long = 20,
        ) {
            val policy = runBlocking { BackupPolicyRepository(context).getPolicy() }
            val request = createRequest(
                policy = policy,
                manualTrigger = manualTrigger,
                initialDelaySeconds = debounceSeconds,
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun enqueueNow(
            context: Context,
            manualTrigger: Boolean,
        ) {
            val policy = runBlocking { BackupPolicyRepository(context).getPolicy() }
            val request = createRequest(
                policy = policy,
                manualTrigger = manualTrigger,
                initialDelaySeconds = 0,
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun createRequest(
            policy: com.imagenext.core.model.BackupPolicy,
            manualTrigger: Boolean,
            initialDelaySeconds: Long,
        ) = OneTimeWorkRequestBuilder<MediaUploadWorker>()
            .setInputData(workDataOf(KEY_MANUAL_TRIGGER to manualTrigger))
            .setConstraints(buildConstraints(policy))
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        private fun buildConstraints(policy: com.imagenext.core.model.BackupPolicy): Constraints {
            val requireCharging =
                policy.syncMode == BackupSyncMode.WHEN_CHARGING ||
                    policy.powerPolicy == com.imagenext.core.model.BackupPowerPolicy.REQUIRE_CHARGING
            val requireDeviceIdle =
                policy.powerPolicy == com.imagenext.core.model.BackupPowerPolicy.REQUIRE_DEVICE_IDLE

            val networkType = when (policy.networkPolicy) {
                BackupNetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
                BackupNetworkPolicy.WIFI_OR_MOBILE -> NetworkType.CONNECTED
            }

            val builder = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresCharging(requireCharging)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.setRequiresDeviceIdle(requireDeviceIdle)
            }
            return builder.build()
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    private sealed interface UploadOutcome {
        data class Success(
            val remotePath: String,
            val kind: UploadSuccessKind,
        ) : UploadOutcome
        data class Failure(
            val transient: Boolean,
            val errorCode: String,
        ) : UploadOutcome
    }

    private enum class UploadSuccessKind {
        UPLOADED,
        SKIPPED_EXISTING,
        DELETED,
    }
}

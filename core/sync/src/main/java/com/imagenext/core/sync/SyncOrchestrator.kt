package com.imagenext.core.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.dao.ReadyThumbnailReference
import com.imagenext.core.model.BackupPolicy
import com.imagenext.core.model.BackupRunState
import com.imagenext.core.model.BackupScheduleType
import com.imagenext.core.model.BackupSyncMode
import com.imagenext.core.model.BackupSyncState
import com.imagenext.core.model.UploadStatus
import com.imagenext.core.model.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Sync scheduling and policy coordinator.
 *
 * Orchestrates background metadata indexing and thumbnail generation
 * using WorkManager. Provides reactive sync state and progress for UI.
 *
 * Features:
 * - Unique work prevents duplicate sync jobs.
 * - Chains thumbnail worker after metadata sync.
 * - Network constraint ensures work only runs with connectivity.
 * - Exponential backoff for retries.
 */
class SyncOrchestrator(
    private val context: Context,
) {

    private val workManager = WorkManager.getInstance(context)
    private val mediaDao by lazy { AppDatabase.getInstance(context).mediaDao() }
    private val uploadQueueDao by lazy { AppDatabase.getInstance(context).uploadQueueDao() }
    private val syncPrefs by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _syncState = MutableStateFlow(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    private val _backupState = MutableStateFlow(BackupSyncState())
    val backupState: StateFlow<BackupSyncState> = _backupState.asStateFlow()

    private var dominantErrorSnapshot: DominantErrorSnapshot? = null

    /** Snapshot used for UI diagnostics while tuning sync behavior. */
    data class SyncDebugSnapshot(
        val syncState: SyncState,
        val libraryState: WorkInfo.State?,
        val thumbnailState: WorkInfo.State?,
        val pendingThumbnailCount: Int,
        val exhaustedFailureCount: Int,
        val dominantExhaustedError: String? = null,
        val dominantExhaustedErrorCount: Int = 0,
    ) {
        fun toDebugLine(): String {
            val library = libraryState?.name ?: "NONE"
            val thumbs = thumbnailState?.name ?: "NONE"
            val topError = dominantExhaustedError?.let { "$it($dominantExhaustedErrorCount)" } ?: "none"
            return "sync=$syncState lib=$library thumbs=$thumbs pending=$pendingThumbnailCount exhausted=$exhaustedFailureCount topError=$topError"
        }
    }

    /**
     * Observes the sync work state from WorkManager.
     */
    fun observeSyncState(): Flow<SyncState> {
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(LibrarySyncWorker.WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(ThumbnailWorker.WORK_NAME),
            mediaDao.observePendingThumbnailCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS),
            mediaDao.observeExhaustedThumbnailFailureCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS),
        ) { libraryInfos, thumbnailInfos, pendingThumbnailCount, exhaustedFailureCount ->
            val latestLibraryInfo = latestInfoForWorker(
                infos = libraryInfos,
                workerTag = LibrarySyncWorker::class.java.name,
            )
            val latestThumbnailInfo = latestInfoForWorker(
                infos = thumbnailInfos,
                workerTag = ThumbnailWorker::class.java.name,
            )

            mapWorkInfoToSyncState(
                latestLibraryInfo = latestLibraryInfo,
                latestThumbnailInfo = latestThumbnailInfo,
                pendingThumbnailCount = pendingThumbnailCount,
                exhaustedFailureCount = exhaustedFailureCount,
            )
        }
            .distinctUntilChanged()
            .onEach { state -> _syncState.value = state }
    }

    /**
     * Observes sync state with low-level diagnostics for UI debugging.
     */
    fun observeSyncDiagnostics(): Flow<SyncDebugSnapshot> {
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(LibrarySyncWorker.WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(ThumbnailWorker.WORK_NAME),
            mediaDao.observePendingThumbnailCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS),
            mediaDao.observeExhaustedThumbnailFailureCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS),
        ) { libraryInfos, thumbnailInfos, pendingThumbnailCount, exhaustedFailureCount ->
            val latestLibraryInfo = latestInfoForWorker(
                infos = libraryInfos,
                workerTag = LibrarySyncWorker::class.java.name,
            )
            val latestThumbnailInfo = latestInfoForWorker(
                infos = thumbnailInfos,
                workerTag = ThumbnailWorker::class.java.name,
            )

            val dominantErrorDetails = resolveDominantErrorDetails(exhaustedFailureCount)
            val dominantError = dominantErrorDetails.first
            val dominantErrorCount = dominantErrorDetails.second

            val state = mapWorkInfoToSyncState(
                latestLibraryInfo = latestLibraryInfo,
                latestThumbnailInfo = latestThumbnailInfo,
                pendingThumbnailCount = pendingThumbnailCount,
                exhaustedFailureCount = exhaustedFailureCount,
            )
            _syncState.value = state

            SyncDebugSnapshot(
                syncState = state,
                libraryState = latestLibraryInfo?.state,
                thumbnailState = latestThumbnailInfo?.state,
                pendingThumbnailCount = pendingThumbnailCount,
                exhaustedFailureCount = exhaustedFailureCount,
                dominantExhaustedError = dominantError,
                dominantExhaustedErrorCount = dominantErrorCount,
            )
        }.distinctUntilChanged()
    }

    /**
     * Observes backup upload state from worker history + queue counts.
     */
    fun observeBackupState(): Flow<BackupSyncState> {
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(MediaUploadWorker.WORK_NAME),
            uploadQueueDao.observeCountByStatus(UploadStatus.PENDING.name),
            uploadQueueDao.observeCountByStatus(UploadStatus.FAILED.name),
        ) { uploadInfos, pendingCount, failedCount ->
            val latestUploadInfo = latestInfoForWorker(
                infos = uploadInfos,
                workerTag = MediaUploadWorker::class.java.name,
            )
            val latestReportableTerminalInfo = latestReportableTerminalInfoForWorker(
                infos = uploadInfos,
                workerTag = MediaUploadWorker::class.java.name,
            )
            mapWorkInfoToBackupState(
                latestUploadInfo = latestUploadInfo,
                latestReportableTerminalInfo = latestReportableTerminalInfo,
                pendingCount = pendingCount,
                failedCount = failedCount,
            )
        }
            .distinctUntilChanged()
            .onEach { state -> _backupState.value = state }
    }

    /**
     * Schedules a sync for all selected folders.
     *
     * Enqueues [LibrarySyncWorker] and proactively primes thumbnail backfill.
     * Thumbnail generation can then overlap with metadata sync as items arrive.
     */
    fun scheduleSyncForFolders() {
        enqueueLibrarySync(existingWorkPolicy = ExistingWorkPolicy.REPLACE)
    }

    /**
     * Requests a foreground-safe metadata refresh.
     *
     * Uses KEEP policy to avoid interrupting any in-flight library sync while
     * still allowing fresh sync work to be enqueued once the previous run has
     * completed.
     */
    fun requestSyncNow() {
        enqueueLibrarySync(existingWorkPolicy = ExistingWorkPolicy.KEEP)
    }

    /**
     * Triggers a combined manual sync (download + local detect + upload).
     */
    fun requestCombinedSyncNow() {
        requestSyncNow()
        LocalMediaDetectorWorker.enqueue(
            context = context,
            manualTrigger = true,
            initialDelaySeconds = 0,
            policy = ExistingWorkPolicy.REPLACE,
        )
        MediaUploadWorker.enqueueNow(
            context = context,
            manualTrigger = true,
        )
    }

    /**
     * Applies periodic backup scheduling from current policy.
     */
    fun applyBackupScheduling(policy: BackupPolicy) {
        workManager.cancelUniqueWork(BackupScheduledKickWorker.WORK_NAME)
        workManager.cancelUniqueWork(LocalMediaDetectorWorker.PERIODIC_WORK_NAME)

        if (!policy.enabled) return
        if (!policy.backupRootSelectedByUser) return
        if (policy.syncMode == BackupSyncMode.MANUAL_ONLY) return

        if (policy.autoUploadNewMedia) {
            LocalMediaDetectorWorker.schedulePeriodic(context = context, intervalHours = DETECTOR_INTERVAL_HOURS)
            // Launch pass to pick up recent captures near app open.
            LocalMediaDetectorWorker.enqueue(
                context = context,
                manualTrigger = false,
                initialDelaySeconds = 0,
                policy = ExistingWorkPolicy.REPLACE,
            )
        }

        val periodicRequest = when (policy.syncMode) {
            BackupSyncMode.SCHEDULED -> {
                val intervalHours = when (policy.scheduleType) {
                    BackupScheduleType.INTERVAL_HOURS -> policy.scheduleIntervalHours.coerceIn(2, 24).toLong()
                    BackupScheduleType.DAILY_TIME -> 24L
                }
                val builder = PeriodicWorkRequestBuilder<BackupScheduledKickWorker>(
                    intervalHours,
                    TimeUnit.HOURS,
                )
                if (policy.scheduleType == BackupScheduleType.DAILY_TIME) {
                    val initialDelayMs = computeDelayUntilDailyTime(
                        hour = policy.dailyHour,
                        minute = policy.dailyMinute,
                    )
                    builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
                builder
                    .setConstraints(createBackupConstraints(policy))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .build()
            }

            BackupSyncMode.WHEN_CHARGING -> {
                PeriodicWorkRequestBuilder<BackupScheduledKickWorker>(
                    1L,
                    TimeUnit.HOURS,
                )
                    .setConstraints(createBackupConstraints(policy))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS,
                    )
                    .build()
            }

            BackupSyncMode.MANUAL_ONLY -> return
        }

        workManager.enqueueUniquePeriodicWork(
            BackupScheduledKickWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest,
        )
    }

    /**
     * Enqueues upload worker with standard debounce.
     */
    fun enqueueDebouncedUpload(manualTrigger: Boolean = false) {
        MediaUploadWorker.enqueueDebounced(
            context = context,
            manualTrigger = manualTrigger,
        )
    }

    /**
     * Launch-triggered detector pass used for auto-upload pickup.
     */
    fun kickBackupDetectorOnLaunch() {
        LocalMediaDetectorWorker.enqueue(
            context = context,
            manualTrigger = false,
            initialDelaySeconds = 0,
            policy = ExistingWorkPolicy.REPLACE,
        )
    }

    /**
     * Ensures background auto-sync is scheduled for recurring library updates.
     */
    fun ensureAutoSyncScheduled() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoSyncKickWorker>(
            AUTO_SYNC_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            AutoSyncKickWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues thumbnail backfill only when retryable thumbnail backlog exists.
     * Used by Photos entry to self-heal partially indexed libraries.
     */
    suspend fun scheduleThumbnailBackfillIfNeeded(requeueExhaustedFailures: Boolean = false) {
        applyOneTimeNoFullPhotoCacheMigrationIfNeeded()
        applyOneTimeImageThumbnailTranscodeRetryV2IfNeeded()
        applyOneTimeSkippedVideoRequeueIfNeeded()

        if (requeueExhaustedFailures) {
            withContext(Dispatchers.IO) {
                mediaDao.requeueExhaustedThumbnailFailures(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
            }
        } else {
            withContext(Dispatchers.IO) {
                mediaDao.requeueExhaustedThumbnailFailuresByError(
                    maxRetryCount = ThumbnailWorker.MAX_RETRY_ATTEMPTS,
                    errorCode = TRANSIENT_ERROR_UNREACHABLE,
                )
            }
        }
        val pendingCount = withContext(Dispatchers.IO) {
            mediaDao.getPendingThumbnailCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
        }
        if (pendingCount <= 0) return
        logPerf("sync_schedule_thumbnail_backfill pending=$pendingCount requeueExhausted=$requeueExhaustedFailures")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        workManager.enqueueUniqueWork(
            ThumbnailWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            createThumbnailRequest(constraints),
        )
    }

    private suspend fun applyOneTimeNoFullPhotoCacheMigrationIfNeeded() {
        if (syncPrefs.getBoolean(KEY_NO_FULL_PHOTO_CACHE_V1_APPLIED, false)) return

        try {
            val stats = withContext(Dispatchers.IO) {
                val remoteCacheCleanup = clearLegacyRemoteImageDiskCaches(
                    cacheRootDir = context.cacheDir,
                    legacyDirNames = LEGACY_REMOTE_IMAGE_CACHE_DIR_NAMES,
                )
                val thumbnailReset = reconcileReadyThumbnailReferences(
                    references = mediaDao.getReadyThumbnailReferences(),
                    thumbnailCacheDir = File(context.cacheDir, ThumbnailWorker.THUMBNAIL_DIR),
                    maxThumbnailBytes = MAX_PERSISTED_THUMBNAIL_BYTES,
                    resetThumbnailState = mediaDao::resetThumbnailState,
                )
                NoFullPhotoCacheMigrationStats(
                    clearedRemoteDirs = remoteCacheCleanup.clearedDirCount,
                    resetThumbnailCount = thumbnailReset.resetCount,
                    deletedBytes = remoteCacheCleanup.deletedBytes + thumbnailReset.deletedBytes,
                )
            }

            syncPrefs.edit().putBoolean(KEY_NO_FULL_PHOTO_CACHE_V1_APPLIED, true).apply()
            logPerf(
                "sync_no_full_photo_cache_v1 " +
                    "clearedRemoteDirs=${stats.clearedRemoteDirs} " +
                    "resetThumbs=${stats.resetThumbnailCount} " +
                    "deletedBytes=${stats.deletedBytes}"
            )
        } catch (e: Exception) {
            logPerf("sync_no_full_photo_cache_v1_retry reason=${e.javaClass.simpleName}")
        }
    }

    private suspend fun applyOneTimeImageThumbnailTranscodeRetryV2IfNeeded() {
        if (syncPrefs.getBoolean(KEY_IMAGE_THUMBNAIL_TRANSCODE_RETRY_V2_APPLIED, false)) return

        val requeued = withContext(Dispatchers.IO) {
            mediaDao.requeueExhaustedThumbnailFailures(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
        }
        syncPrefs.edit().putBoolean(KEY_IMAGE_THUMBNAIL_TRANSCODE_RETRY_V2_APPLIED, true).apply()
        logPerf("sync_image_thumb_transcode_retry_v2 requeuedExhausted=$requeued")
    }

    private suspend fun applyOneTimeSkippedVideoRequeueIfNeeded() {
        if (syncPrefs.getBoolean(KEY_VIDEO_SKIPPED_REQUEUE_V1_APPLIED, false)) return

        val skippedBefore = withContext(Dispatchers.IO) {
            mediaDao.getSkippedVideoThumbnailCount()
        }
        val requeued = if (skippedBefore > 0) {
            withContext(Dispatchers.IO) {
                mediaDao.requeueSkippedVideoThumbnails()
            }
        } else {
            0
        }

        syncPrefs.edit().putBoolean(KEY_VIDEO_SKIPPED_REQUEUE_V1_APPLIED, true).apply()
        logPerf("sync_video_skipped_requeue_once skippedBefore=$skippedBefore requeued=$requeued")
    }

    /**
     * Cancels any active sync work.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(LibrarySyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(ThumbnailWorker.WORK_NAME)
        workManager.cancelUniqueWork(AutoSyncKickWorker.WORK_NAME)
        workManager.cancelUniqueWork(MediaUploadWorker.WORK_NAME)
        workManager.cancelUniqueWork(LocalMediaDetectorWorker.WORK_NAME)
        workManager.cancelUniqueWork(LocalMediaDetectorWorker.PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(BackupScheduledKickWorker.WORK_NAME)
        _syncState.value = SyncState.Idle
        _backupState.value = BackupSyncState()
    }

    /**
     * Retries sync by re-scheduling the full workflow.
     */
    fun retrySync() {
        scheduleSyncForFolders()
    }

    private suspend fun resolveDominantErrorDetails(exhaustedFailureCount: Int): Pair<String?, Int> {
        if (exhaustedFailureCount <= 0) {
            dominantErrorSnapshot = null
            return null to 0
        }

        dominantErrorSnapshot?.let { snapshot ->
            if (snapshot.exhaustedFailureCount == exhaustedFailureCount) {
                return snapshot.errorCode to snapshot.errorCount
            }
        }

        val dominantError = withContext(Dispatchers.IO) {
            mediaDao.getDominantExhaustedThumbnailError(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
        }
        val dominantErrorCount = if (dominantError != null) {
            withContext(Dispatchers.IO) {
                mediaDao.getExhaustedThumbnailFailureCountByError(
                    ThumbnailWorker.MAX_RETRY_ATTEMPTS,
                    dominantError,
                )
            }
        } else {
            0
        }

        dominantErrorSnapshot = DominantErrorSnapshot(
            exhaustedFailureCount = exhaustedFailureCount,
            errorCode = dominantError,
            errorCount = dominantErrorCount,
        )
        return dominantError to dominantErrorCount
    }

    private fun mapWorkInfoToSyncState(
        latestLibraryInfo: WorkInfo?,
        latestThumbnailInfo: WorkInfo?,
        pendingThumbnailCount: Int,
        exhaustedFailureCount: Int,
    ): SyncState {
        val latestLibraryState = latestLibraryInfo?.state
        val latestThumbnailState = latestThumbnailInfo?.state

        // Treat enqueued/blocked library work as in-flight to avoid stale failed-state flashes
        // when a fresh sync has already been scheduled.
        if (
            latestLibraryState == WorkInfo.State.RUNNING ||
            latestLibraryState == WorkInfo.State.ENQUEUED ||
            latestLibraryState == WorkInfo.State.BLOCKED
        ) {
            return SyncState.Running
        }

        // Thumbnail-only backfill should not show as hard "loading".
        if (latestThumbnailState == WorkInfo.State.RUNNING || latestThumbnailState == WorkInfo.State.ENQUEUED) {
            return SyncState.Partial
        }

        if (pendingThumbnailCount > 0) return SyncState.Partial

        val hasHistory = latestLibraryInfo != null || latestThumbnailInfo != null
        if (!hasHistory) {
            return when {
                exhaustedFailureCount > 0 -> SyncState.Partial
                else -> SyncState.Idle
            }
        }

        val latestStates = buildList {
            latestLibraryState?.let { add(it) }
            latestThumbnailState?.let { add(it) }
        }

        return when {
            latestStates.all { it == WorkInfo.State.SUCCEEDED } -> {
                if (exhaustedFailureCount > 0) {
                    return SyncState.Partial
                }

                // Check if the sync result was partial
                val lastOutput = latestLibraryInfo?.outputData
                val statusStr = lastOutput?.getString(LibrarySyncWorker.KEY_STATUS)
                if (statusStr == SyncState.Partial.name) SyncState.Partial
                else SyncState.Completed
            }
            latestStates.any { it == WorkInfo.State.FAILED } -> SyncState.Failed
            latestStates.any { it == WorkInfo.State.CANCELLED } -> SyncState.Idle
            exhaustedFailureCount > 0 -> SyncState.Partial
            else -> SyncState.Idle
        }
    }

    private fun mapWorkInfoToBackupState(
        latestUploadInfo: WorkInfo?,
        latestReportableTerminalInfo: WorkInfo?,
        pendingCount: Int,
        failedCount: Int,
    ): BackupSyncState {
        val uploadState = latestUploadInfo?.state
        val runState = when {
            uploadState == WorkInfo.State.RUNNING ||
                uploadState == WorkInfo.State.ENQUEUED ||
                uploadState == WorkInfo.State.BLOCKED -> BackupRunState.RUNNING
            uploadState == WorkInfo.State.FAILED -> BackupRunState.FAILED
            uploadState == WorkInfo.State.SUCCEEDED && pendingCount == 0 && failedCount == 0 -> BackupRunState.COMPLETED
            failedCount > 0 -> BackupRunState.FAILED
            pendingCount > 0 -> BackupRunState.RUNNING
            else -> BackupRunState.IDLE
        }

        val terminalOutput = latestReportableTerminalInfo?.outputData
        val lastRunFailedCount = terminalOutput?.getInt(MediaUploadWorker.KEY_FAILED_COUNT, 0) ?: 0
        val lastRunResult = when (latestReportableTerminalInfo?.state) {
            WorkInfo.State.SUCCEEDED -> if (lastRunFailedCount > 0) BackupRunState.FAILED else BackupRunState.COMPLETED
            WorkInfo.State.FAILED -> BackupRunState.FAILED
            WorkInfo.State.CANCELLED -> BackupRunState.IDLE
            else -> BackupRunState.IDLE
        }

        val lastError = latestUploadInfo?.outputData?.getString(MediaUploadWorker.KEY_ERROR)
            ?: terminalOutput?.getString(MediaUploadWorker.KEY_ERROR)
        return BackupSyncState(
            runState = runState,
            pendingCount = pendingCount,
            failedCount = failedCount,
            lastError = lastError,
            hasLastRun = latestReportableTerminalInfo != null,
            lastRunResult = lastRunResult,
            lastRunUploadedCount = terminalOutput?.getInt(MediaUploadWorker.KEY_UPLOADED_COUNT, 0) ?: 0,
            lastRunSkippedCount = terminalOutput?.getInt(MediaUploadWorker.KEY_SKIPPED_COUNT, 0) ?: 0,
            lastRunDeletedCount = terminalOutput?.getInt(MediaUploadWorker.KEY_DELETED_COUNT, 0) ?: 0,
            lastRunFailedCount = lastRunFailedCount,
            lastRunProcessedCount = terminalOutput?.getInt(MediaUploadWorker.KEY_PROCESSED_COUNT, 0) ?: 0,
            lastRunFinishedAt = terminalOutput?.getLong(MediaUploadWorker.KEY_FINISHED_AT, 0L)
                ?.takeIf { it > 0L },
        )
    }

    private fun latestInfoForWorker(infos: List<WorkInfo>, workerTag: String): WorkInfo? {
        return infos
            .asReversed()
            .firstOrNull { info -> info.tags.contains(workerTag) }
            ?: infos.lastOrNull()
    }

    private fun latestReportableTerminalInfoForWorker(infos: List<WorkInfo>, workerTag: String): WorkInfo? {
        return infos
            .asReversed()
            .firstOrNull { info ->
                info.tags.contains(workerTag) &&
                    info.state.isFinished &&
                    info.outputData.getBoolean(MediaUploadWorker.KEY_REPORTABLE_RUN, true)
            }
    }

    private fun createLibrarySyncRequest(constraints: Constraints) =
        OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

    private fun enqueueLibrarySync(existingWorkPolicy: ExistingWorkPolicy) {
        logPerf("sync_schedule_full policy=$existingWorkPolicy")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = createLibrarySyncRequest(constraints)
        workManager.enqueueUniqueWork(
            LibrarySyncWorker.WORK_NAME,
            existingWorkPolicy,
            syncRequest,
        )
        ThumbnailWorker.enqueueBackfill(context)

        _syncState.value = SyncState.Running
    }

    private fun createThumbnailRequest(constraints: Constraints) =
        OneTimeWorkRequestBuilder<ThumbnailWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

    private fun createBackupConstraints(policy: BackupPolicy): Constraints {
        val requiresCharging = policy.syncMode == BackupSyncMode.WHEN_CHARGING ||
            policy.powerPolicy == com.imagenext.core.model.BackupPowerPolicy.REQUIRE_CHARGING
        val requiresIdle = policy.powerPolicy == com.imagenext.core.model.BackupPowerPolicy.REQUIRE_DEVICE_IDLE

        val networkType = when (policy.networkPolicy) {
            com.imagenext.core.model.BackupNetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
            com.imagenext.core.model.BackupNetworkPolicy.WIFI_OR_MOBILE -> NetworkType.CONNECTED
        }

        val builder = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(requiresCharging)
        builder.setRequiresDeviceIdle(requiresIdle)
        return builder.build()
    }

    private fun computeDelayUntilDailyTime(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(0L)
    }

    private fun logPerf(message: String) {
        Log.d(PERF_TAG, message)
    }

    private data class DominantErrorSnapshot(
        val exhaustedFailureCount: Int,
        val errorCode: String?,
        val errorCount: Int,
    )

    private data class NoFullPhotoCacheMigrationStats(
        val clearedRemoteDirs: Int,
        val resetThumbnailCount: Int,
        val deletedBytes: Long,
    )

    private companion object {
        const val PERF_TAG = "ImageNextPerf"
        const val TRANSIENT_ERROR_UNREACHABLE = "unreachable"
        const val AUTO_SYNC_INTERVAL_MINUTES = 15L
        const val DETECTOR_INTERVAL_HOURS = 1L
        const val SYNC_PREFS_NAME = "sync_orchestrator_prefs"
        const val KEY_VIDEO_SKIPPED_REQUEUE_V1_APPLIED = "video_skipped_requeue_v1_applied"
        const val KEY_NO_FULL_PHOTO_CACHE_V1_APPLIED = "no_full_photo_cache_v1_applied"
        const val KEY_IMAGE_THUMBNAIL_TRANSCODE_RETRY_V2_APPLIED = "image_thumbnail_transcode_retry_v2_applied"
        const val MAX_PERSISTED_THUMBNAIL_BYTES = 1_048_576L
        val LEGACY_REMOTE_IMAGE_CACHE_DIR_NAMES = listOf("image_cache", "coil_image_cache")
    }
}

internal data class RemoteCacheCleanupStats(
    val clearedDirCount: Int,
    val deletedBytes: Long,
)

internal data class ThumbnailReconciliationStats(
    val resetCount: Int,
    val deletedBytes: Long,
)

internal fun clearLegacyRemoteImageDiskCaches(
    cacheRootDir: File,
    legacyDirNames: List<String>,
): RemoteCacheCleanupStats {
    var clearedDirCount = 0
    var deletedBytes = 0L

    for (dirName in legacyDirNames) {
        val candidate = File(cacheRootDir, dirName)
        if (!candidate.exists()) continue

        val candidateBytes = candidate.safeSizeBytes()
        if (!candidate.deleteRecursively() && candidate.exists()) {
            throw IOException("Failed to clear legacy remote cache dir: ${candidate.absolutePath}")
        }
        clearedDirCount++
        deletedBytes += candidateBytes
    }

    return RemoteCacheCleanupStats(
        clearedDirCount = clearedDirCount,
        deletedBytes = deletedBytes,
    )
}

internal suspend fun reconcileReadyThumbnailReferences(
    references: List<ReadyThumbnailReference>,
    thumbnailCacheDir: File,
    maxThumbnailBytes: Long,
    resetThumbnailState: suspend (String) -> Unit,
): ThumbnailReconciliationStats {
    var resetCount = 0
    var deletedBytes = 0L

    for (reference in references) {
        val thumbnailFile = File(reference.thumbnailPath)
        if (!thumbnailFile.isWithinDirectory(thumbnailCacheDir)) continue

        val exists = thumbnailFile.exists()
        val shouldReset = !exists || thumbnailFile.length() > maxThumbnailBytes
        if (!shouldReset) continue

        if (exists) {
            val bytes = thumbnailFile.length()
            if (!thumbnailFile.delete() && thumbnailFile.exists()) {
                throw IOException("Failed to delete oversized thumbnail: ${thumbnailFile.absolutePath}")
            }
            deletedBytes += bytes
        }

        resetThumbnailState(reference.remotePath)
        resetCount++
    }

    return ThumbnailReconciliationStats(
        resetCount = resetCount,
        deletedBytes = deletedBytes,
    )
}

private fun File.isWithinDirectory(rootDir: File): Boolean {
    return try {
        val candidatePath = canonicalFile.toPath().normalize()
        val rootPath = rootDir.canonicalFile.toPath().normalize()
        candidatePath.startsWith(rootPath)
    } catch (_: IOException) {
        false
    }
}

private fun File.safeSizeBytes(): Long {
    if (!exists()) return 0L
    if (isFile) return length()

    var bytes = 0L
    walkTopDown().forEach { file ->
        if (file.isFile) bytes += file.length()
    }
    return bytes
}

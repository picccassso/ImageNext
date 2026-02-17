package com.imagenext.core.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.model.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

    private val _syncState = MutableStateFlow(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

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
        return observeSyncDiagnostics().map { it.syncState }
    }

    /**
     * Observes sync state with low-level diagnostics for UI debugging.
     */
    fun observeSyncDiagnostics(): Flow<SyncDebugSnapshot> {
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(LibrarySyncWorker.WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(ThumbnailWorker.WORK_NAME),
        ) { libraryInfos, thumbnailInfos ->
            val latestLibraryInfo = latestInfoForWorker(
                infos = libraryInfos,
                workerTag = LibrarySyncWorker::class.java.name,
            )
            val latestThumbnailInfo = latestInfoForWorker(
                infos = thumbnailInfos,
                workerTag = ThumbnailWorker::class.java.name,
            )

            val mediaDao = AppDatabase.getInstance(context).mediaDao()
            val pendingThumbnailCount = withContext(Dispatchers.IO) {
                mediaDao.getPendingThumbnailCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
            }
            val exhaustedFailureCount = withContext(Dispatchers.IO) {
                mediaDao.getExhaustedThumbnailFailureCount(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
            }
            val dominantError = if (exhaustedFailureCount > 0) {
                withContext(Dispatchers.IO) {
                    mediaDao.getDominantExhaustedThumbnailError(ThumbnailWorker.MAX_RETRY_ATTEMPTS)
                }
            } else {
                null
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
        }
    }

    /**
     * Schedules a sync for all selected folders.
     *
     * Enqueues a [LibrarySyncWorker] then chains a [ThumbnailWorker].
     * Uses unique work to prevent duplicate jobs.
     */
    fun scheduleSyncForFolders() {
        logPerf("sync_schedule_full")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = createLibrarySyncRequest(constraints)
        val thumbnailRequest = createThumbnailRequest(constraints)

        workManager
            .beginUniqueWork(
                LibrarySyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest,
            )
            .then(thumbnailRequest)
            .enqueue()

        _syncState.value = SyncState.Running
    }

    /**
     * Enqueues thumbnail backfill only when retryable thumbnail backlog exists.
     * Used by Photos entry to self-heal partially indexed libraries.
     */
    suspend fun scheduleThumbnailBackfillIfNeeded(requeueExhaustedFailures: Boolean = false) {
        val mediaDao = AppDatabase.getInstance(context).mediaDao()
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

    /**
     * Cancels any active sync work.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(LibrarySyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(ThumbnailWorker.WORK_NAME)
        _syncState.value = SyncState.Idle
    }

    /**
     * Retries sync by re-scheduling the full workflow.
     */
    fun retrySync() {
        scheduleSyncForFolders()
    }

    private fun mapWorkInfoToSyncState(
        latestLibraryInfo: WorkInfo?,
        latestThumbnailInfo: WorkInfo?,
        pendingThumbnailCount: Int,
        exhaustedFailureCount: Int,
    ): SyncState {
        val latestLibraryState = latestLibraryInfo?.state
        val latestThumbnailState = latestThumbnailInfo?.state

        // Running is reserved for active metadata indexing.
        if (latestLibraryState == WorkInfo.State.RUNNING || latestLibraryState == WorkInfo.State.ENQUEUED) {
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

    private fun latestInfoForWorker(infos: List<WorkInfo>, workerTag: String): WorkInfo? {
        return infos
            .asReversed()
            .firstOrNull { info -> info.tags.contains(workerTag) }
            ?: infos.lastOrNull()
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

    private fun createThumbnailRequest(constraints: Constraints) =
        OneTimeWorkRequestBuilder<ThumbnailWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

    private fun logPerf(message: String) {
        Log.d(PERF_TAG, message)
    }

    private companion object {
        const val PERF_TAG = "ImageNextPerf"
        const val TRANSIENT_ERROR_UNREACHABLE = "unreachable"
    }
}

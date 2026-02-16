package com.imagenext.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.imagenext.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    /**
     * Observes the sync work state from WorkManager.
     */
    fun observeSyncState(): Flow<SyncState> {
        return workManager.getWorkInfosForUniqueWorkFlow(LibrarySyncWorker.WORK_NAME)
            .map { workInfos ->
                val state = mapWorkInfoToSyncState(workInfos)
                _syncState.value = state
                state
            }
    }

    /**
     * Schedules a sync for all selected folders.
     *
     * Enqueues a [LibrarySyncWorker] then chains a [ThumbnailWorker].
     * Uses unique work to prevent duplicate jobs.
     */
    fun scheduleSyncForFolders() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        val thumbnailRequest = OneTimeWorkRequestBuilder<ThumbnailWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

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
     * Cancels any active sync work.
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(LibrarySyncWorker.WORK_NAME)
        _syncState.value = SyncState.Idle
    }

    /**
     * Retries sync by re-scheduling the full workflow.
     */
    fun retrySync() {
        scheduleSyncForFolders()
    }

    private fun mapWorkInfoToSyncState(workInfos: List<WorkInfo>): SyncState {
        if (workInfos.isEmpty()) return SyncState.Idle

        val states = workInfos.map { it.state }

        return when {
            states.any { it == WorkInfo.State.RUNNING } -> SyncState.Running
            states.any { it == WorkInfo.State.ENQUEUED } -> SyncState.Running
            states.all { it == WorkInfo.State.SUCCEEDED } -> {
                // Check if the sync result was partial
                val lastOutput = workInfos.lastOrNull()?.outputData
                val statusStr = lastOutput?.getString(LibrarySyncWorker.KEY_STATUS)
                if (statusStr == SyncState.Partial.name) SyncState.Partial
                else SyncState.Completed
            }
            states.any { it == WorkInfo.State.FAILED } -> SyncState.Failed
            states.any { it == WorkInfo.State.CANCELLED } -> SyncState.Idle
            else -> SyncState.Idle
        }
    }
}

package com.imagenext.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.data.BackupPolicyRepository
import com.imagenext.core.model.BackupSyncMode
import java.util.concurrent.TimeUnit

/**
 * Periodic kick worker that schedules one combined backup pass.
 */
class BackupScheduledKickWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val policy = BackupPolicyRepository(applicationContext).getPolicy()
        if (!policy.enabled) return Result.success(workDataOf(KEY_REASON to "disabled"))
        if (!policy.backupRootSelectedByUser) {
            return Result.success(workDataOf(KEY_REASON to "backup_root_not_selected"))
        }
        if (policy.syncMode == BackupSyncMode.MANUAL_ONLY) {
            return Result.success(workDataOf(KEY_REASON to "manual_mode"))
        }

        val libraryRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            LibrarySyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            libraryRequest,
        )

        if (policy.autoUploadNewMedia) {
            LocalMediaDetectorWorker.enqueue(
                context = applicationContext,
                manualTrigger = false,
                initialDelaySeconds = 0,
                policy = ExistingWorkPolicy.REPLACE,
            )
        }
        MediaUploadWorker.enqueueDebounced(applicationContext, manualTrigger = false)

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "backup_scheduled_kick"
        const val KEY_REASON = "reason"
    }
}

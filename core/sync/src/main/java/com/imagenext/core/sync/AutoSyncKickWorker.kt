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
import com.imagenext.core.database.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that kicks off a normal library sync when the user has a
 * valid session and at least one selected folder.
 */
class AutoSyncKickWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val sessionRepo = SyncDependencies.getSessionRepository(applicationContext)
            ?: return Result.success()
        val hasSession = sessionRepo.getSession() != null
        if (!hasSession) return Result.success()

        val selectedFolderCount = AppDatabase.getInstance(applicationContext).folderDao().getSelectedCount()
        if (selectedFolderCount <= 0) {
            return Result.success(workDataOf(KEY_REASON to "no_selected_folders"))
        }

        val workRequest = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
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
            workRequest,
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "library_sync_auto_kick"
        private const val KEY_REASON = "reason"
    }
}

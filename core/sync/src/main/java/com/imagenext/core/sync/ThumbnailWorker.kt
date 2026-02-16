package com.imagenext.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Background thumbnail fetch worker.
 *
 * Fetches thumbnails for media items that don't have a cached local thumbnail.
 * Uses the Nextcloud preview API to download scaled preview images.
 *
 * Features:
 * - Deduplication: skips items that already have a cached thumbnail.
 * - Bounded batch size: processes a limited number per execution to prevent overload.
 * - Cache writes to app-private directory.
 */
class ThumbnailWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val mediaDao = database.mediaDao()

        val sessionRepo = SyncDependencies.getSessionRepository(applicationContext)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))
        val session = sessionRepo.getSession()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))

        // Get items that need thumbnails (bounded batch)
        val items = mediaDao.getItemsWithoutThumbnail(BATCH_SIZE)

        if (items.isEmpty()) {
            return Result.success(workDataOf(KEY_FETCHED_COUNT to 0))
        }

        // Ensure cache directory exists
        val cacheDir = File(applicationContext.cacheDir, THUMBNAIL_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        var fetchedCount = 0

        for (item in items) {
            if (isStopped) break

            try {
                val thumbnailFile = File(cacheDir, thumbnailFileName(item.remotePath))

                // Deduplication: skip if file already exists on disk
                if (thumbnailFile.exists()) {
                    mediaDao.updateThumbnailPath(item.remotePath, thumbnailFile.absolutePath)
                    fetchedCount++
                    continue
                }

                // Fetch from Nextcloud preview API
                val encodedPath = java.net.URLEncoder.encode(item.remotePath, "UTF-8")
                val previewUrl = "${session.serverUrl}/index.php/core/preview?file=$encodedPath&x=$THUMBNAIL_SIZE&y=$THUMBNAIL_SIZE&a=1"

                val request = Request.Builder()
                    .url(previewUrl)
                    .header("Authorization", Credentials.basic(session.loginName, session.appPassword))
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { inputStream ->
                            FileOutputStream(thumbnailFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        mediaDao.updateThumbnailPath(item.remotePath, thumbnailFile.absolutePath)
                        fetchedCount++
                    }
                    // On failure, skip this item — it will be retried next cycle
                }
            } catch (_: Exception) {
                // Skip failed items; they'll be retried on next worker execution
            }

            // Report progress
            setProgress(
                workDataOf(
                    KEY_PROGRESS_CURRENT to fetchedCount,
                    KEY_PROGRESS_TOTAL to items.size,
                )
            )
        }

        return Result.success(workDataOf(KEY_FETCHED_COUNT to fetchedCount))
    }

    /**
     * Generates a safe filename for a thumbnail based on the remote path.
     * Uses hash to avoid filesystem path issues.
     */
    private fun thumbnailFileName(remotePath: String): String {
        val hash = remotePath.hashCode().toUInt().toString(16)
        val extension = remotePath.substringAfterLast('.', "jpg")
        return "thumb_${hash}.$extension"
    }

    companion object {
        const val WORK_NAME = "thumbnail_fetch"
        const val KEY_FETCHED_COUNT = "fetched_count"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        /** Number of thumbnails to fetch per worker execution. */
        const val BATCH_SIZE = 50

        /** Thumbnail preview size in pixels. */
        const val THUMBNAIL_SIZE = 256

        /** Directory name within app cache for thumbnails. */
        const val THUMBNAIL_DIR = "thumbnails"
    }
}

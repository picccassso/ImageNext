package com.imagenext.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.model.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
        val workerStartMs = SystemClock.elapsedRealtime()
        val database = AppDatabase.getInstance(applicationContext)
        val mediaDao = database.mediaDao()

        val sessionRepo = SyncDependencies.getSessionRepository(applicationContext)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))
        val session = sessionRepo.getSession()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No active session"))

        // Ensure cache directory exists
        val cacheDir = File(applicationContext.cacheDir, THUMBNAIL_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        var totalFetched = 0
        var totalProcessed = 0
        val readyUpdates = mutableListOf<Pair<String, String>>()
        val failedUpdates = mutableListOf<Pair<String, String>>()
        var firstReadyCommitted = false
        var lastDbFlushAtMs = workerStartMs
        var dbFlushCount = 0

        // Process each candidate at most once per worker execution.
        // Retries happen on subsequent scheduled runs, not tight loops.
        val items = mediaDao.getItemsNeedingThumbnail(
            limit = BATCH_SIZE * MAX_BATCHES_PER_RUN,
            maxRetryCount = MAX_RETRY_ATTEMPTS,
        )
        val totalToProcess = items.size
        logPerf("thumbnail_worker_start candidates=$totalToProcess")
        var firstSuccessAtMs: Long? = null
        var previewSuccessCount = 0
        var webDavFallbackCount = 0
        var cacheHitCount = 0

        for (chunk in items.chunked(NETWORK_CONCURRENCY)) {
            if (isStopped) break

            val results = coroutineScope {
                chunk.map { item ->
                    async(Dispatchers.IO) {
                        fetchThumbnailForItem(
                            item = item,
                            session = session,
                            cacheDir = cacheDir,
                        )
                    }
                }.awaitAll()
            }

            for (result in results) {
                if (isStopped) break
                totalProcessed++

                when (result) {
                    is ThumbnailFetchResult.RetryUnreachable -> {
                        // DNS/connectivity outages are transient; avoid exhausting per-item retries.
                        val hadPendingBeforeRetryFlush = readyUpdates.isNotEmpty() || failedUpdates.isNotEmpty()
                        flushThumbnailStateUpdates(database, mediaDao, readyUpdates, failedUpdates)
                        if (hadPendingBeforeRetryFlush) {
                            dbFlushCount++
                        }
                        logPerf(
                            "thumbnail_worker_retry_unreachable " +
                                "processed=$totalProcessed " +
                                "fetched=$totalFetched " +
                                "durationMs=${elapsedSince(workerStartMs)}"
                        )
                        return Result.retry()
                    }

                    is ThumbnailFetchResult.Failed -> {
                        failedUpdates += result.remotePath to result.errorCode
                    }

                    is ThumbnailFetchResult.Ready -> {
                        readyUpdates += result.remotePath to result.thumbnailPath
                        totalFetched++

                        when (result.source) {
                            ThumbnailSource.Cache -> cacheHitCount++
                            ThumbnailSource.Preview -> previewSuccessCount++
                            ThumbnailSource.WebDav -> webDavFallbackCount++
                        }

                        if (firstSuccessAtMs == null) {
                            firstSuccessAtMs = elapsedSince(workerStartMs)
                        }

                        if (!firstReadyCommitted) {
                            flushThumbnailStateUpdates(database, mediaDao, readyUpdates, failedUpdates)
                            firstReadyCommitted = true
                            lastDbFlushAtMs = SystemClock.elapsedRealtime()
                            dbFlushCount++
                        }
                    }
                }

                // Report progress in coarse intervals to avoid excessive WorkManager churn.
                if (
                    totalProcessed == totalToProcess ||
                    totalProcessed % PROGRESS_UPDATE_INTERVAL == 0
                ) {
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_CURRENT to totalProcessed,
                            KEY_PROGRESS_TOTAL to totalToProcess,
                        )
                    )
                }

                if (
                    shouldFlushPendingUpdates(
                        totalProcessed = totalProcessed,
                        readyUpdates = readyUpdates,
                        failedUpdates = failedUpdates,
                        lastFlushAtMs = lastDbFlushAtMs,
                    )
                ) {
                    flushThumbnailStateUpdates(database, mediaDao, readyUpdates, failedUpdates)
                    lastDbFlushAtMs = SystemClock.elapsedRealtime()
                    dbFlushCount++
                }
            }
        }

        val hadPendingBeforeFinalFlush = readyUpdates.isNotEmpty() || failedUpdates.isNotEmpty()
        flushThumbnailStateUpdates(database, mediaDao, readyUpdates, failedUpdates)
        if (hadPendingBeforeFinalFlush) {
            dbFlushCount++
        }

        val pendingCount = mediaDao.getPendingThumbnailCount(MAX_RETRY_ATTEMPTS)
        val exhaustedFailureCount = mediaDao.getExhaustedThumbnailFailureCount(MAX_RETRY_ATTEMPTS)

        if (!isStopped && pendingCount > 0) {
            enqueueFollowUpWork()
        }

        logPerf(
            "thumbnail_worker_finish " +
                "processed=$totalProcessed " +
                "fetched=$totalFetched " +
                "pending=$pendingCount " +
                "exhausted=$exhaustedFailureCount " +
                "previewSuccess=$previewSuccessCount " +
                "webDavFallback=$webDavFallbackCount " +
                "cacheHit=$cacheHitCount " +
                "dbFlushes=$dbFlushCount " +
                "firstSuccessMs=${firstSuccessAtMs ?: -1L} " +
                "durationMs=${elapsedSince(workerStartMs)}"
        )

        return Result.success(
            workDataOf(
                KEY_FETCHED_COUNT to totalFetched,
                KEY_PENDING_COUNT to pendingCount,
                KEY_EXHAUSTED_FAILURE_COUNT to exhaustedFailureCount,
            )
        )
    }

    private fun buildPreviewUrl(serverUrl: String, remotePath: String): String {
        val encodedPath = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "${serverUrl}/index.php/core/preview?file=$encodedPath&x=$THUMBNAIL_SIZE&y=$THUMBNAIL_SIZE&a=1"
    }

    private fun buildWebDavUrl(serverUrl: String, username: String, remotePath: String): String {
        val encodedPath = encodePathForWebDav(remotePath)
        return "${serverUrl}/remote.php/dav/files/$username/$encodedPath"
    }

    private fun encodePathForWebDav(path: String): String {
        if (path.isBlank()) return ""
        return path
            .trimStart('/')
            .split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
    }

    private fun buildAuthenticatedRequest(
        url: String,
        username: String,
        password: String,
        includeOcsHeader: Boolean = false,
    ): Request {
        return Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
            .apply {
                if (includeOcsHeader) {
                    header("OCS-APIRequest", "true")
                }
            }
            .get()
            .build()
    }

    private fun writeResponseToFile(response: okhttp3.Response, destination: File): Boolean {
        val body = response.body
        return try {
            body.byteStream().use { inputStream ->
                FileOutputStream(destination).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
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

    private fun enqueueFollowUpWork() {
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                createWorkRequest(),
            )
    }

    private fun classifyError(error: Exception): String {
        return when (error) {
            is UnknownHostException -> ERROR_UNREACHABLE
            is SSLException -> ERROR_SSL
            else -> ERROR_IO
        }
    }

    private fun fetchThumbnailForItem(
        item: MediaItemEntity,
        session: AuthSession,
        cacheDir: File,
    ): ThumbnailFetchResult {
        return try {
            val thumbnailFile = File(cacheDir, thumbnailFileName(item.remotePath))

            // Deduplication: keep DB in sync if file already exists.
            if (thumbnailFile.exists()) {
                return ThumbnailFetchResult.Ready(
                    remotePath = item.remotePath,
                    thumbnailPath = thumbnailFile.absolutePath,
                    source = ThumbnailSource.Cache,
                )
            }

            // Fetch from Nextcloud preview API.
            val previewUrl = buildPreviewUrl(session.serverUrl, item.remotePath)
            val previewRequest = buildAuthenticatedRequest(
                url = previewUrl,
                username = session.loginName,
                password = session.appPassword,
                includeOcsHeader = true,
            )
            var lastFailureCode: String? = null
            val fetchedFromPreview = client.newCall(previewRequest).execute().use { response ->
                if (response.isSuccessful && writeResponseToFile(response, thumbnailFile)) {
                    true
                } else {
                    lastFailureCode = "preview_http_${response.code}"
                    false
                }
            }

            if (fetchedFromPreview) {
                return ThumbnailFetchResult.Ready(
                    remotePath = item.remotePath,
                    thumbnailPath = thumbnailFile.absolutePath,
                    source = ThumbnailSource.Preview,
                )
            }

            val webDavUrl = buildWebDavUrl(
                serverUrl = session.serverUrl,
                username = session.loginName,
                remotePath = item.remotePath,
            )
            val webDavRequest = buildAuthenticatedRequest(
                url = webDavUrl,
                username = session.loginName,
                password = session.appPassword,
            )
            val fetchedFromWebDav = client.newCall(webDavRequest).execute().use { response ->
                if (response.isSuccessful && writeResponseToFile(response, thumbnailFile)) {
                    true
                } else {
                    lastFailureCode = "${lastFailureCode ?: "preview_io"}_webdav_http_${response.code}"
                    false
                }
            }

            if (fetchedFromWebDav) {
                ThumbnailFetchResult.Ready(
                    remotePath = item.remotePath,
                    thumbnailPath = thumbnailFile.absolutePath,
                    source = ThumbnailSource.WebDav,
                )
            } else {
                ThumbnailFetchResult.Failed(
                    remotePath = item.remotePath,
                    errorCode = lastFailureCode ?: ERROR_IO,
                )
            }
        } catch (e: Exception) {
            val errorCode = classifyError(e)
            if (errorCode == ERROR_UNREACHABLE) {
                ThumbnailFetchResult.RetryUnreachable
            } else {
                ThumbnailFetchResult.Failed(
                    remotePath = item.remotePath,
                    errorCode = errorCode,
                )
            }
        }
    }

    private fun elapsedSince(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    private fun logPerf(message: String) {
        Log.d(PERF_TAG, message)
    }

    private sealed interface ThumbnailFetchResult {
        data class Ready(
            val remotePath: String,
            val thumbnailPath: String,
            val source: ThumbnailSource,
        ) : ThumbnailFetchResult

        data class Failed(
            val remotePath: String,
            val errorCode: String,
        ) : ThumbnailFetchResult

        data object RetryUnreachable : ThumbnailFetchResult
    }

    private enum class ThumbnailSource {
        Cache,
        Preview,
        WebDav,
    }

    private fun shouldFlushPendingUpdates(
        totalProcessed: Int,
        readyUpdates: List<Pair<String, String>>,
        failedUpdates: List<Pair<String, String>>,
        lastFlushAtMs: Long,
    ): Boolean {
        if (readyUpdates.isEmpty() && failedUpdates.isEmpty()) return false
        if (totalProcessed > 0 && totalProcessed % DB_UPDATE_BATCH_SIZE == 0) return true
        val ageMs = SystemClock.elapsedRealtime() - lastFlushAtMs
        return ageMs >= DB_UPDATE_MAX_STALENESS_MS
    }

    private suspend fun flushThumbnailStateUpdates(
        database: AppDatabase,
        mediaDao: com.imagenext.core.database.dao.MediaDao,
        readyUpdates: MutableList<Pair<String, String>>,
        failedUpdates: MutableList<Pair<String, String>>,
    ) {
        if (readyUpdates.isEmpty() && failedUpdates.isEmpty()) return

        database.withTransaction {
            readyUpdates.forEach { (remotePath, thumbnailPath) ->
                mediaDao.markThumbnailReady(remotePath, thumbnailPath)
            }
            failedUpdates.forEach { (remotePath, errorCode) ->
                mediaDao.markThumbnailFailed(remotePath, errorCode)
            }
        }

        readyUpdates.clear()
        failedUpdates.clear()
    }

    companion object {
        const val WORK_NAME = "thumbnail_fetch"
        const val KEY_FETCHED_COUNT = "fetched_count"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PENDING_COUNT = "pending_count"
        const val KEY_EXHAUSTED_FAILURE_COUNT = "exhausted_failure_count"

        /** Number of thumbnails to fetch per batch. */
        const val BATCH_SIZE = 60

        /** Max batches processed in a single worker execution. */
        const val MAX_BATCHES_PER_RUN = 6

        /** Max retry attempts per media item before being marked exhausted. */
        const val MAX_RETRY_ATTEMPTS = 3

        /** Progress emit interval (items) to reduce UI/update thrash. */
        private const val PROGRESS_UPDATE_INTERVAL = 30

        /** DB update commit interval (items) to reduce paging invalidations during sync. */
        private const val DB_UPDATE_BATCH_SIZE = 80

        /** Maximum staleness before pending DB updates are flushed. */
        private const val DB_UPDATE_MAX_STALENESS_MS = 2000L

        /** Number of concurrent network fetches per loop to reduce total thumbnail latency. */
        private const val NETWORK_CONCURRENCY = 4

        /** Thumbnail preview size in pixels. */
        const val THUMBNAIL_SIZE = 256

        /** Directory name within app cache for thumbnails. */
        const val THUMBNAIL_DIR = "thumbnails"
        private const val PERF_TAG = "ImageNextPerf"
        private const val ERROR_UNREACHABLE = "unreachable"
        private const val ERROR_SSL = "ssl"
        private const val ERROR_IO = "io"

        /**
         * Enqueues thumbnail backfill work if one is not already running/enqueued.
         * Used to overlap thumbnail generation with ongoing metadata scans.
         */
        fun enqueueBackfill(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    createWorkRequest(),
                )
        }

        private fun createWorkRequest() =
            OneTimeWorkRequestBuilder<ThumbnailWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()
    }
}

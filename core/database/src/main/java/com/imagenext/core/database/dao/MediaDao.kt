package com.imagenext.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_FAILED
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_PENDING
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_READY
import com.imagenext.core.database.entity.THUMBNAIL_STATUS_SKIPPED
import kotlinx.coroutines.flow.Flow

/** Lightweight projection for READY thumbnail reconciliation. */
data class ReadyThumbnailReference(
    val remotePath: String,
    val thumbnailPath: String,
)

/**
 * Data access object for media items.
 *
 * Provides query patterns for timeline display, viewer lookups,
 * and sync-driven upsert operations.
 */
@Dao
interface MediaDao {

    /** Observes all media items ordered by timeline date (newest first). */
    @Query("SELECT * FROM media_items ORDER BY timelineSortKey DESC")
    fun getAllMedia(): Flow<List<MediaItemEntity>>

    /** Observes media items for a specific folder. */
    @Query(
        "SELECT * FROM media_items " +
            "WHERE folderPath = :folderPath " +
            "ORDER BY timelineSortKey DESC"
    )
    fun getMediaByFolder(folderPath: String): Flow<List<MediaItemEntity>>

    /** Inserts or updates media items (upsert by remotePath PK). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    /** Deletes all media items belonging to a specific folder. */
    @Query("DELETE FROM media_items WHERE folderPath = :folderPath")
    suspend fun deleteByFolder(folderPath: String)

    /** Returns the total count of media items. */
    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun getCount(): Int

    /** Returns media items that still need thumbnail generation. */
    @Query(
        "SELECT * FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_PENDING' " +
            "OR (thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' AND thumbnailRetryCount < :maxRetryCount) " +
            "ORDER BY CASE thumbnailStatus " +
            "WHEN '$THUMBNAIL_STATUS_PENDING' THEN 0 ELSE 1 END, " +
            "timelineSortKey DESC " +
            "LIMIT :limit"
    )
    suspend fun getItemsNeedingThumbnail(limit: Int, maxRetryCount: Int): List<MediaItemEntity>

    /** Marks a thumbnail as successfully cached. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailPath = :thumbnailPath, " +
            "thumbnailStatus = '$THUMBNAIL_STATUS_READY', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = NULL " +
            "WHERE remotePath = :remotePath"
    )
    suspend fun markThumbnailReady(remotePath: String, thumbnailPath: String)

    /** Returns READY items that currently reference an on-disk thumbnail path. */
    @Query(
        "SELECT remotePath, thumbnailPath FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_READY' " +
            "AND thumbnailPath IS NOT NULL " +
            "AND thumbnailPath != ''"
    )
    suspend fun getReadyThumbnailReferences(): List<ReadyThumbnailReference>

    /** Marks a thumbnail fetch as failed and increments retry count. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailStatus = '$THUMBNAIL_STATUS_FAILED', " +
            "thumbnailRetryCount = thumbnailRetryCount + 1, " +
            "thumbnailLastError = :errorCode " +
            "WHERE remotePath = :remotePath"
    )
    suspend fun markThumbnailFailed(remotePath: String, errorCode: String)

    /** Marks thumbnail generation as intentionally skipped for unsupported media. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailPath = NULL, " +
            "thumbnailStatus = '$THUMBNAIL_STATUS_SKIPPED', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = :reasonCode " +
            "WHERE remotePath = :remotePath"
    )
    suspend fun markThumbnailSkipped(remotePath: String, reasonCode: String)

    /** Resets thumbnail state to pending when metadata changes invalidate cache. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailPath = NULL, " +
            "thumbnailStatus = '$THUMBNAIL_STATUS_PENDING', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = NULL " +
            "WHERE remotePath = :remotePath"
    )
    suspend fun resetThumbnailState(remotePath: String)

    /** Count of retryable thumbnail backlog items. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_PENDING' " +
            "OR (thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' AND thumbnailRetryCount < :maxRetryCount)"
    )
    suspend fun getPendingThumbnailCount(maxRetryCount: Int): Int

    /** Reactive count of retryable thumbnail backlog items. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_PENDING' " +
            "OR (thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' AND thumbnailRetryCount < :maxRetryCount)"
    )
    fun observePendingThumbnailCount(maxRetryCount: Int): Flow<Int>

    /** Count of permanently failed thumbnail items after retry budget is exhausted. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' AND thumbnailRetryCount >= :maxRetryCount"
    )
    suspend fun getExhaustedThumbnailFailureCount(maxRetryCount: Int): Int

    /** Reactive count of permanently failed thumbnail items. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' AND thumbnailRetryCount >= :maxRetryCount"
    )
    fun observeExhaustedThumbnailFailureCount(maxRetryCount: Int): Flow<Int>

    /** Most common error code among exhausted thumbnail failures. */
    @Query(
        "SELECT thumbnailLastError FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' " +
            "AND thumbnailRetryCount >= :maxRetryCount " +
            "AND thumbnailLastError IS NOT NULL " +
            "GROUP BY thumbnailLastError " +
            "ORDER BY COUNT(*) DESC " +
            "LIMIT 1"
    )
    suspend fun getDominantExhaustedThumbnailError(maxRetryCount: Int): String?

    /** Number of exhausted failures for a specific error code. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' " +
            "AND thumbnailRetryCount >= :maxRetryCount " +
            "AND thumbnailLastError = :errorCode"
    )
    suspend fun getExhaustedThumbnailFailureCountByError(maxRetryCount: Int, errorCode: String): Int

    /** Re-queues exhausted thumbnail failures for another backfill pass. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailStatus = '$THUMBNAIL_STATUS_PENDING', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = NULL " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' " +
            "AND thumbnailRetryCount >= :maxRetryCount"
    )
    suspend fun requeueExhaustedThumbnailFailures(maxRetryCount: Int): Int

    /** Re-queues exhausted failures for a specific error category. */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailStatus = '$THUMBNAIL_STATUS_PENDING', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = NULL " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_FAILED' " +
            "AND thumbnailRetryCount >= :maxRetryCount " +
            "AND thumbnailLastError = :errorCode"
    )
    suspend fun requeueExhaustedThumbnailFailuresByError(maxRetryCount: Int, errorCode: String): Int

    /** Re-queues skipped video thumbnails for retry (used after fallback behavior upgrades). */
    @Query(
        "UPDATE media_items " +
            "SET thumbnailStatus = '$THUMBNAIL_STATUS_PENDING', " +
            "thumbnailRetryCount = 0, " +
            "thumbnailLastError = NULL " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_SKIPPED' " +
            "AND mimeType LIKE 'video/%'"
    )
    suspend fun requeueSkippedVideoThumbnails(): Int

    /** Returns count of skipped video thumbnails. */
    @Query(
        "SELECT COUNT(*) FROM media_items " +
            "WHERE thumbnailStatus = '$THUMBNAIL_STATUS_SKIPPED' " +
            "AND mimeType LIKE 'video/%'"
    )
    suspend fun getSkippedVideoThumbnailCount(): Int

    /** Paged timeline query ordered by timeline date (newest first). */
    @Query("SELECT * FROM media_items ORDER BY timelineSortKey DESC")
    fun getTimelinePaged(): PagingSource<Int, MediaItemEntity>

    /** Paged query for all photo-type media. */
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'image/%' ORDER BY timelineSortKey DESC")
    fun getPhotosPaged(): PagingSource<Int, MediaItemEntity>

    /** Paged query for all video-type media. */
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'video/%' ORDER BY timelineSortKey DESC")
    fun getVideosPaged(): PagingSource<Int, MediaItemEntity>

    /** Paged query for media items belonging to a specific manual album. */
    @Query(
        "SELECT m.* FROM media_items m " +
            "INNER JOIN album_media_cross_ref am ON am.mediaRemotePath = m.remotePath " +
            "WHERE am.albumId = :albumId " +
            "ORDER BY m.timelineSortKey DESC"
    )
    fun getAlbumMediaPaged(albumId: Long): PagingSource<Int, MediaItemEntity>

    /** Returns a single media item by its remote path. */
    @Query("SELECT * FROM media_items WHERE remotePath = :remotePath LIMIT 1")
    suspend fun getByRemotePath(remotePath: String): MediaItemEntity?

    /** Returns all media items ordered by timeline date descending (for viewer index lookup). */
    @Query("SELECT * FROM media_items ORDER BY timelineSortKey DESC")
    suspend fun getAllMediaList(): List<MediaItemEntity>

    /** Returns all photos ordered by timeline date descending. */
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'image/%' ORDER BY timelineSortKey DESC")
    suspend fun getPhotosList(): List<MediaItemEntity>

    /** Returns all videos ordered by timeline date descending. */
    @Query("SELECT * FROM media_items WHERE mimeType LIKE 'video/%' ORDER BY timelineSortKey DESC")
    suspend fun getVideosList(): List<MediaItemEntity>

    /** Returns media items for a specific manual album ordered by timeline date descending. */
    @Query(
        "SELECT m.* FROM media_items m " +
            "INNER JOIN album_media_cross_ref am ON am.mediaRemotePath = m.remotePath " +
            "WHERE am.albumId = :albumId " +
            "ORDER BY m.timelineSortKey DESC"
    )
    suspend fun getAlbumMediaList(albumId: Long): List<MediaItemEntity>

    /** Returns all matching media items for bulk merge logic. */
    @Query("SELECT * FROM media_items WHERE remotePath IN (:remotePaths)")
    suspend fun getByRemotePaths(remotePaths: List<String>): List<MediaItemEntity>
}

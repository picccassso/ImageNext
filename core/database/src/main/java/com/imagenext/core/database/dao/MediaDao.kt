package com.imagenext.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for media items.
 *
 * Provides query patterns for timeline display, viewer lookups,
 * and sync-driven upsert operations.
 */
@Dao
interface MediaDao {

    /** Observes all media items ordered by last modified (newest first). */
    @Query("SELECT * FROM media_items ORDER BY lastModified DESC")
    fun getAllMedia(): Flow<List<MediaItemEntity>>

    /** Observes media items for a specific folder. */
    @Query("SELECT * FROM media_items WHERE folderPath = :folderPath ORDER BY lastModified DESC")
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

    /** Returns media items that have no cached thumbnail. */
    @Query("SELECT * FROM media_items WHERE thumbnailPath IS NULL LIMIT :limit")
    suspend fun getItemsWithoutThumbnail(limit: Int): List<MediaItemEntity>

    /** Updates the thumbnail path for a specific media item. */
    @Query("UPDATE media_items SET thumbnailPath = :thumbnailPath WHERE remotePath = :remotePath")
    suspend fun updateThumbnailPath(remotePath: String, thumbnailPath: String)

    /** Paged timeline query ordered by last modified (newest first). */
    @Query("SELECT * FROM media_items ORDER BY lastModified DESC")
    fun getTimelinePaged(): PagingSource<Int, MediaItemEntity>
}

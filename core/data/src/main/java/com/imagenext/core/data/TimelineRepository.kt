package com.imagenext.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Timeline query contract and implementation.
 *
 * Provides paged access to media items for the Photos timeline,
 * ordered by lastModified descending (newest first).
 */
class TimelineRepository(
    private val mediaDao: MediaDao,
) {

    /**
     * Returns a paged flow of [MediaItem]s for timeline display.
     *
     * Room automatically invalidates the [PagingSource] when
     * the underlying table changes, so new items arriving from
     * sync will trigger updates without manual refresh.
     */
    fun getTimelinePaged(): Flow<PagingData<MediaItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 30,
                initialLoadSize = 90,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { mediaDao.getTimelinePaged() },
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                MediaItem(
                    remotePath = entity.remotePath,
                    fileName = entity.fileName,
                    mimeType = entity.mimeType,
                    size = entity.size,
                    lastModified = entity.lastModified,
                    captureTimestamp = entity.captureTimestamp,
                    etag = entity.etag,
                    thumbnailPath = entity.thumbnailPath,
                    folderPath = entity.folderPath,
                )
            }
        }
    }

    /** Returns the total count of media items. */
    suspend fun getMediaCount(): Int = mediaDao.getCount()
}

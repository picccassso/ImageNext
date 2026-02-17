package com.imagenext.core.data

import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository implementation for media metadata.
 *
 * Provides reactive access to the local media database for
 * timeline display and viewer lookups.
 */
class MediaRepositoryImpl(
    private val mediaDao: MediaDao,
) {

    /** Observes all media items ordered by timeline timestamp (newest first). */
    fun getAllMedia(): Flow<List<MediaItem>> {
        return mediaDao.getAllMedia().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /** Observes media items for a specific folder. */
    fun getMediaForFolder(folderPath: String): Flow<List<MediaItem>> {
        return mediaDao.getMediaByFolder(folderPath).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /** Returns the total count of media items. */
    suspend fun getMediaCount(): Int {
        return mediaDao.getCount()
    }

    private fun MediaItemEntity.toDomainModel(): MediaItem {
        return MediaItem(
            remotePath = remotePath,
            fileName = fileName,
            mimeType = mimeType,
            size = size,
            lastModified = lastModified,
            captureTimestamp = captureTimestamp,
            etag = etag,
            thumbnailPath = thumbnailPath,
            folderPath = folderPath,
        )
    }
}

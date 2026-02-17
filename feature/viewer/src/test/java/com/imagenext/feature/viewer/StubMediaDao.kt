package com.imagenext.feature.viewer

import androidx.paging.PagingSource
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub [MediaDao] for testing.
 * Never called at runtime — the [FakeViewerRepository] overrides all methods.
 */
class StubMediaDao : MediaDao {
    override fun getAllMedia(): Flow<List<MediaItemEntity>> = emptyFlow()
    override fun getMediaByFolder(folderPath: String): Flow<List<MediaItemEntity>> = emptyFlow()
    override suspend fun upsertAll(items: List<MediaItemEntity>) {}
    override suspend fun deleteByFolder(folderPath: String) {}
    override suspend fun getCount(): Int = 0
    override suspend fun getItemsNeedingThumbnail(limit: Int, maxRetryCount: Int): List<MediaItemEntity> = emptyList()
    override suspend fun markThumbnailReady(remotePath: String, thumbnailPath: String) {}
    override suspend fun markThumbnailFailed(remotePath: String, errorCode: String) {}
    override suspend fun resetThumbnailState(remotePath: String) {}
    override suspend fun getPendingThumbnailCount(maxRetryCount: Int): Int = 0
    override suspend fun getExhaustedThumbnailFailureCount(maxRetryCount: Int): Int = 0
    override suspend fun getDominantExhaustedThumbnailError(maxRetryCount: Int): String? = null
    override suspend fun getExhaustedThumbnailFailureCountByError(maxRetryCount: Int, errorCode: String): Int = 0
    override suspend fun requeueExhaustedThumbnailFailures(maxRetryCount: Int): Int = 0
    override suspend fun requeueExhaustedThumbnailFailuresByError(maxRetryCount: Int, errorCode: String): Int = 0
    override fun getTimelinePaged(): PagingSource<Int, MediaItemEntity> {
        throw UnsupportedOperationException("Stub")
    }
    override suspend fun getByRemotePath(remotePath: String): MediaItemEntity? = null
    override suspend fun getAllMediaList(): List<MediaItemEntity> = emptyList()
    override suspend fun getByRemotePaths(remotePaths: List<String>): List<MediaItemEntity> = emptyList()
}

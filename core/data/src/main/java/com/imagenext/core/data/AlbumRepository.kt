package com.imagenext.core.data

import android.database.sqlite.SQLiteConstraintException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.AlbumEntity
import com.imagenext.core.database.entity.AlbumMediaCrossRefEntity
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.model.Album
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.Locale

data class AlbumPickerItem(
    val id: Long,
    val displayName: String,
    val mediaCount: Int,
)

sealed interface AlbumWriteResult {
    data class Success(val albumId: Long) : AlbumWriteResult
    data object EmptyName : AlbumWriteResult
    data class DuplicateName(val existingAlbumId: Long) : AlbumWriteResult
    data object NotFound : AlbumWriteResult
}

sealed interface AddMediaResult {
    data object Added : AddMediaResult
    data object AlreadyInAlbum : AddMediaResult
    data object AlbumNotFound : AddMediaResult
    data object MediaNotFound : AddMediaResult
}

sealed interface RemoveMediaResult {
    data object Removed : RemoveMediaResult
    data object NotInAlbum : RemoveMediaResult
}

/** Repository for manual album CRUD and smart/local album membership operations. */
class AlbumRepository(
    private val albumDao: AlbumDao,
    private val mediaDao: MediaDao,
) {

    fun observeAlbums(): Flow<List<Album>> {
        val userAlbums = albumDao.observeAlbumSummaries().map { rows ->
            rows.map { row ->
                Album(
                    id = row.id,
                    displayName = row.displayName,
                    mediaCount = row.mediaCount,
                    coverThumbnailPath = row.coverThumbnailPath,
                    isSystem = false,
                )
            }
        }
        val allMedia = mediaDao.getAllMedia().map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }

        return combine(userAlbums, allMedia) { manualAlbums, media ->
            val photos = media.filter { it.isImage }
            val videos = media.filter { it.isVideo }

            val systemAlbums = listOf(
                Album(
                    id = SYSTEM_ALBUM_RECENTS_ID,
                    displayName = SYSTEM_ALBUM_RECENTS_NAME,
                    mediaCount = media.size,
                    coverThumbnailPath = media.firstOrNull()?.thumbnailPath,
                    isSystem = true,
                ),
                Album(
                    id = SYSTEM_ALBUM_PHOTOS_ID,
                    displayName = SYSTEM_ALBUM_PHOTOS_NAME,
                    mediaCount = photos.size,
                    coverThumbnailPath = photos.firstOrNull()?.thumbnailPath,
                    isSystem = true,
                ),
                Album(
                    id = SYSTEM_ALBUM_VIDEOS_ID,
                    displayName = SYSTEM_ALBUM_VIDEOS_NAME,
                    mediaCount = videos.size,
                    coverThumbnailPath = videos.firstOrNull()?.thumbnailPath,
                    isSystem = true,
                ),
            )

            systemAlbums + manualAlbums
        }
    }

    fun observeAlbumPicker(): Flow<List<AlbumPickerItem>> {
        return albumDao.observeAlbumPickerRows().map { rows ->
            rows.map { row ->
                AlbumPickerItem(
                    id = row.id,
                    displayName = row.displayName,
                    mediaCount = row.mediaCount,
                )
            }
        }
    }

    suspend fun createAlbum(name: String): AlbumWriteResult {
        val sanitized = sanitizeName(name) ?: return AlbumWriteResult.EmptyName
        val normalized = normalizeName(sanitized)

        val reservedAlbumId = SYSTEM_ALBUM_NAME_TO_ID[normalized]
        if (reservedAlbumId != null) return AlbumWriteResult.DuplicateName(reservedAlbumId)

        val existing = albumDao.getByNormalizedName(normalized)
        if (existing != null) return AlbumWriteResult.DuplicateName(existing.albumId)

        val now = System.currentTimeMillis()
        return try {
            val insertedId = albumDao.insertAlbum(
                AlbumEntity(
                    name = sanitized,
                    normalizedName = normalized,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            AlbumWriteResult.Success(insertedId)
        } catch (_: SQLiteConstraintException) {
            val duplicate = albumDao.getByNormalizedName(normalized)
            if (duplicate != null) {
                AlbumWriteResult.DuplicateName(duplicate.albumId)
            } else {
                AlbumWriteResult.DuplicateName(-1)
            }
        }
    }

    suspend fun renameAlbum(albumId: Long, newName: String): AlbumWriteResult {
        if (isSystemAlbum(albumId)) return AlbumWriteResult.NotFound

        val current = albumDao.getAlbum(albumId) ?: return AlbumWriteResult.NotFound
        val sanitized = sanitizeName(newName) ?: return AlbumWriteResult.EmptyName
        val normalized = normalizeName(sanitized)

        val reservedAlbumId = SYSTEM_ALBUM_NAME_TO_ID[normalized]
        if (reservedAlbumId != null) return AlbumWriteResult.DuplicateName(reservedAlbumId)

        val duplicate = albumDao.getByNormalizedName(normalized)
        if (duplicate != null && duplicate.albumId != albumId) {
            return AlbumWriteResult.DuplicateName(duplicate.albumId)
        }

        if (current.name == sanitized && current.normalizedName == normalized) {
            return AlbumWriteResult.Success(albumId)
        }

        return try {
            albumDao.renameAlbum(
                albumId = albumId,
                name = sanitized,
                normalizedName = normalized,
                updatedAt = System.currentTimeMillis(),
            )
            AlbumWriteResult.Success(albumId)
        } catch (_: SQLiteConstraintException) {
            val duplicate = albumDao.getByNormalizedName(normalized)
            if (duplicate != null) {
                AlbumWriteResult.DuplicateName(duplicate.albumId)
            } else {
                AlbumWriteResult.DuplicateName(-1)
            }
        }
    }

    suspend fun deleteAlbum(albumId: Long) {
        if (isSystemAlbum(albumId)) return
        albumDao.deleteAlbum(albumId)
    }

    suspend fun addMediaToAlbum(albumId: Long, mediaRemotePath: String): AddMediaResult {
        if (isSystemAlbum(albumId)) return AddMediaResult.AlbumNotFound
        if (albumDao.getAlbum(albumId) == null) return AddMediaResult.AlbumNotFound
        if (mediaDao.getByRemotePath(mediaRemotePath) == null) return AddMediaResult.MediaNotFound

        return try {
            val insertResult = albumDao.insertAlbumMedia(
                AlbumMediaCrossRefEntity(
                    albumId = albumId,
                    mediaRemotePath = mediaRemotePath,
                    addedAt = System.currentTimeMillis(),
                )
            )

            if (insertResult == -1L) {
                AddMediaResult.AlreadyInAlbum
            } else {
                albumDao.touchAlbum(albumId, System.currentTimeMillis())
                AddMediaResult.Added
            }
        } catch (_: SQLiteConstraintException) {
            AddMediaResult.MediaNotFound
        }
    }

    suspend fun removeMediaFromAlbum(albumId: Long, mediaRemotePath: String): RemoveMediaResult {
        if (isSystemAlbum(albumId)) return RemoveMediaResult.NotInAlbum
        val removed = albumDao.removeAlbumMedia(albumId, mediaRemotePath)
        return if (removed > 0) {
            albumDao.touchAlbum(albumId, System.currentTimeMillis())
            RemoveMediaResult.Removed
        } else {
            RemoveMediaResult.NotInAlbum
        }
    }

    fun getAlbumMediaPaged(albumId: Long): Flow<PagingData<MediaItem>> {
        val pagingSourceFactory = when (albumId) {
            SYSTEM_ALBUM_RECENTS_ID -> ({ mediaDao.getTimelinePaged() })
            SYSTEM_ALBUM_PHOTOS_ID -> ({ mediaDao.getPhotosPaged() })
            SYSTEM_ALBUM_VIDEOS_ID -> ({ mediaDao.getVideosPaged() })
            else -> ({ mediaDao.getAlbumMediaPaged(albumId) })
        }

        return Pager(
            config = PagingConfig(
                pageSize = 90,
                prefetchDistance = 60,
                initialLoadSize = 120,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = pagingSourceFactory,
        ).flow.map { pagingData ->
            pagingData.map { entity -> entity.toDomainModel() }
        }
    }

    suspend fun getAlbumMediaOrdered(albumId: Long): List<MediaItem> {
        val entities = when (albumId) {
            SYSTEM_ALBUM_RECENTS_ID -> mediaDao.getAllMediaList()
            SYSTEM_ALBUM_PHOTOS_ID -> mediaDao.getPhotosList()
            SYSTEM_ALBUM_VIDEOS_ID -> mediaDao.getVideosList()
            else -> mediaDao.getAlbumMediaList(albumId)
        }
        return entities.map { entity -> entity.toDomainModel() }
    }

    fun isSystemAlbum(albumId: Long): Boolean {
        return albumId == SYSTEM_ALBUM_RECENTS_ID ||
            albumId == SYSTEM_ALBUM_PHOTOS_ID ||
            albumId == SYSTEM_ALBUM_VIDEOS_ID
    }

    private fun sanitizeName(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        return collapsed.takeIf { it.isNotEmpty() }
    }

    private fun normalizeName(name: String): String {
        return name.lowercase(Locale.ROOT)
    }

    companion object {
        const val SYSTEM_ALBUM_RECENTS_ID: Long = -1
        const val SYSTEM_ALBUM_PHOTOS_ID: Long = -2
        const val SYSTEM_ALBUM_VIDEOS_ID: Long = -3

        const val SYSTEM_ALBUM_RECENTS_NAME = "Recents"
        const val SYSTEM_ALBUM_PHOTOS_NAME = "Photos"
        const val SYSTEM_ALBUM_VIDEOS_NAME = "Videos"

        private val SYSTEM_ALBUM_NAME_TO_ID = mapOf(
            SYSTEM_ALBUM_RECENTS_NAME.lowercase(Locale.ROOT) to SYSTEM_ALBUM_RECENTS_ID,
            SYSTEM_ALBUM_PHOTOS_NAME.lowercase(Locale.ROOT) to SYSTEM_ALBUM_PHOTOS_ID,
            SYSTEM_ALBUM_VIDEOS_NAME.lowercase(Locale.ROOT) to SYSTEM_ALBUM_VIDEOS_ID,
        )
    }
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

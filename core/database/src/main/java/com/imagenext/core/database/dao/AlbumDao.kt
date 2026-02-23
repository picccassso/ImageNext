package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.AlbumEntity
import com.imagenext.core.database.entity.AlbumMediaCrossRefEntity
import kotlinx.coroutines.flow.Flow

/** Row projection for album list cards. */
data class AlbumSummaryRow(
    val id: Long,
    val displayName: String,
    val mediaCount: Int,
    val coverThumbnailPath: String?,
)

/** Row projection for the add-to-album picker UI. */
data class AlbumPickerRow(
    val id: Long,
    val displayName: String,
    val mediaCount: Int,
)

@Dao
interface AlbumDao {
    @Query(
        "SELECT " +
            "a.albumId AS id, " +
            "a.name AS displayName, " +
            "COUNT(m.remotePath) AS mediaCount, " +
            "(" +
            "SELECT m.thumbnailPath FROM media_items m " +
            "INNER JOIN album_media_cross_ref am2 ON am2.mediaRemotePath = m.remotePath " +
            "WHERE am2.albumId = a.albumId " +
            "ORDER BY m.timelineSortKey DESC " +
            "LIMIT 1" +
            ") AS coverThumbnailPath " +
            "FROM albums a " +
            "LEFT JOIN album_media_cross_ref am ON am.albumId = a.albumId " +
            "LEFT JOIN media_items m ON m.remotePath = am.mediaRemotePath " +
            "GROUP BY a.albumId " +
            "ORDER BY a.updatedAt DESC, a.albumId DESC"
    )
    fun observeAlbumSummaries(): Flow<List<AlbumSummaryRow>>

    @Query(
        "SELECT " +
            "a.albumId AS id, " +
            "a.name AS displayName, " +
            "COUNT(m.remotePath) AS mediaCount " +
            "FROM albums a " +
            "LEFT JOIN album_media_cross_ref am ON am.albumId = a.albumId " +
            "LEFT JOIN media_items m ON m.remotePath = am.mediaRemotePath " +
            "GROUP BY a.albumId " +
            "ORDER BY a.name COLLATE NOCASE ASC"
    )
    fun observeAlbumPickerRows(): Flow<List<AlbumPickerRow>>

    @Query("SELECT * FROM albums WHERE albumId = :albumId LIMIT 1")
    suspend fun getAlbum(albumId: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getByNormalizedName(normalizedName: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query(
        "UPDATE albums SET name = :name, normalizedName = :normalizedName, updatedAt = :updatedAt " +
            "WHERE albumId = :albumId"
    )
    suspend fun renameAlbum(
        albumId: Long,
        name: String,
        normalizedName: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE albums SET updatedAt = :updatedAt WHERE albumId = :albumId")
    suspend fun touchAlbum(albumId: Long, updatedAt: Long): Int

    @Query("DELETE FROM albums WHERE albumId = :albumId")
    suspend fun deleteAlbum(albumId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumMedia(ref: AlbumMediaCrossRefEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumMedia(refs: List<AlbumMediaCrossRefEntity>): List<Long>

    @Query(
        "DELETE FROM album_media_cross_ref " +
            "WHERE albumId = :albumId AND mediaRemotePath = :mediaRemotePath"
    )
    suspend fun removeAlbumMedia(albumId: Long, mediaRemotePath: String): Int

    @Query(
        "DELETE FROM album_media_cross_ref " +
            "WHERE albumId = :albumId AND mediaRemotePath IN (:mediaRemotePaths)"
    )
    suspend fun removeAlbumMediaBulk(albumId: Long, mediaRemotePaths: List<String>): Int

    @Query("DELETE FROM album_media_cross_ref WHERE mediaRemotePath IN (:remotePaths)")
    suspend fun deleteMediaRefs(remotePaths: List<String>): Int
}

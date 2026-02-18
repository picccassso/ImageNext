package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.LocalBackupAlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalBackupAlbumDao {

    @Query("SELECT * FROM local_backup_albums ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LocalBackupAlbumEntity>>

    @Query("SELECT * FROM local_backup_albums ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAllList(): List<LocalBackupAlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalBackupAlbumEntity>)

    @Query("DELETE FROM local_backup_albums")
    suspend fun clearAll()
}

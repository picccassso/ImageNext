package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.LocalBackupFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalBackupFolderDao {

    @Query("SELECT * FROM local_backup_folders ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LocalBackupFolderEntity>>

    @Query("SELECT * FROM local_backup_folders ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAllList(): List<LocalBackupFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalBackupFolderEntity)

    @Query("DELETE FROM local_backup_folders WHERE treeUri = :treeUri")
    suspend fun deleteByUri(treeUri: String)

    @Query("DELETE FROM local_backup_folders")
    suspend fun clearAll()
}

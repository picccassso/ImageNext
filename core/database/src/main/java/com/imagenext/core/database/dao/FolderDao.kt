package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for selected folders and sync checkpoints.
 *
 * Provides folder selection persistence and sync checkpoint
 * management for safe incremental sync continuation.
 */
@Dao
interface FolderDao {

    /** Observes all selected folders. */
    @Query("SELECT * FROM selected_folders ORDER BY addedTimestamp DESC")
    fun getSelectedFolders(): Flow<List<SelectedFolderEntity>>

    /** Returns all selected folders synchronously (for sync workers). */
    @Query("SELECT * FROM selected_folders")
    suspend fun getSelectedFoldersList(): List<SelectedFolderEntity>

    /** Inserts a folder selection. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: SelectedFolderEntity)

    /** Removes a folder selection by remote path. */
    @Query("DELETE FROM selected_folders WHERE remotePath = :remotePath")
    suspend fun delete(remotePath: String)

    /** Returns the count of selected folders. */
    @Query("SELECT COUNT(*) FROM selected_folders")
    suspend fun getSelectedCount(): Int

    // -- Sync checkpoints --

    /** Gets the sync checkpoint for a specific folder. */
    @Query("SELECT * FROM sync_checkpoints WHERE folderPath = :folderPath")
    suspend fun getCheckpoint(folderPath: String): SyncCheckpointEntity?

    /** Inserts or updates a sync checkpoint. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: SyncCheckpointEntity)

    /** Deletes the sync checkpoint for a specific folder. */
    @Query("DELETE FROM sync_checkpoints WHERE folderPath = :folderPath")
    suspend fun deleteCheckpoint(folderPath: String)
}

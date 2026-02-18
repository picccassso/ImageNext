package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.imagenext.core.database.entity.UploadQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: UploadQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<UploadQueueEntity>): List<Long>

    @Update
    suspend fun update(item: UploadQueueEntity)

    @Query("SELECT * FROM upload_queue WHERE stableKey = :stableKey AND operation = :operation LIMIT 1")
    suspend fun getByStableKeyAndOperation(stableKey: String, operation: String): UploadQueueEntity?

    @Query(
        "SELECT * FROM upload_queue " +
            "WHERE status = :status AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun getReadyByStatus(status: String, now: Long, limit: Int): List<UploadQueueEntity>

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query(
        "UPDATE upload_queue " +
            "SET status = :status, retryCount = 0, lastError = NULL, " +
            "lastAttemptAt = NULL, nextAttemptAt = 0, updatedAt = :updatedAt " +
            "WHERE status = :fromStatus"
    )
    suspend fun requeueAll(fromStatus: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM upload_queue WHERE status = :status AND updatedAt < :cutoff")
    suspend fun pruneByStatusOlderThan(status: String, cutoff: Long): Int
}

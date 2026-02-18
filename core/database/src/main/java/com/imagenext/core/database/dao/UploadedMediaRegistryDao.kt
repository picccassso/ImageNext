package com.imagenext.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagenext.core.database.entity.UploadedMediaRegistryEntity

@Dao
interface UploadedMediaRegistryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: UploadedMediaRegistryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<UploadedMediaRegistryEntity>)

    @Query("SELECT * FROM uploaded_media_registry WHERE stableKey = :stableKey LIMIT 1")
    suspend fun getByStableKey(stableKey: String): UploadedMediaRegistryEntity?

    @Query("SELECT * FROM uploaded_media_registry WHERE deletedRemotelyAt IS NULL")
    suspend fun getActiveRows(): List<UploadedMediaRegistryEntity>

    @Query("SELECT stableKey FROM uploaded_media_registry WHERE deletedRemotelyAt IS NULL")
    suspend fun getActiveStableKeys(): List<String>

    @Query("UPDATE uploaded_media_registry SET lastSeenAt = :seenAt, lastKnownLocalUri = :localUri WHERE stableKey = :stableKey")
    suspend fun markSeen(stableKey: String, seenAt: Long, localUri: String?)

    @Query("UPDATE uploaded_media_registry SET deletedRemotelyAt = :deletedAt WHERE stableKey = :stableKey")
    suspend fun markDeleted(stableKey: String, deletedAt: Long)
}

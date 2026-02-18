package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local device album (MediaStore bucket) selected for backup.
 */
@Entity(tableName = "local_backup_albums")
data class LocalBackupAlbumEntity(
    @PrimaryKey
    val bucketId: String,
    val displayName: String,
    val addedAt: Long,
)

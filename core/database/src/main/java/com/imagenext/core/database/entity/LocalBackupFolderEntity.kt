package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persisted SAF tree folder selection for backup source scope. */
@Entity(tableName = "local_backup_folders")
data class LocalBackupFolderEntity(
    @PrimaryKey
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

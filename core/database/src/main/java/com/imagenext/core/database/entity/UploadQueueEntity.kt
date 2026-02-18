package com.imagenext.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.imagenext.core.model.UploadOperation
import com.imagenext.core.model.UploadStatus

/**
 * Upload queue row for Nextcloud backup operations.
 */
@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["stableKey", "operation"], unique = true),
        Index(value = ["status", "nextAttemptAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val stableKey: String,
    @ColumnInfo(defaultValue = "UPLOAD")
    val operation: String = UploadOperation.UPLOAD.name,
    val localUri: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val dateTaken: Long? = null,
    val targetRemoteFolder: String,
    val targetFileName: String,
    @ColumnInfo(defaultValue = "PENDING")
    val status: String = UploadStatus.PENDING.name,
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
    val bytesTotal: Long? = null,
    val bytesUploaded: Long? = null,
    val remotePath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val nextAttemptAt: Long = 0,
)

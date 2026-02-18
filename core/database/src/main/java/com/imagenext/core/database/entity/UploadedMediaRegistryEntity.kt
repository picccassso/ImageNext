package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Registry of media previously uploaded by ImageNext.
 *
 * This supports dedupe and mirror-delete reconciliation.
 */
@Entity(
    tableName = "uploaded_media_registry",
    indices = [
        Index(value = ["remotePath"]),
        Index(value = ["bucketId"]),
        Index(value = ["deletedRemotelyAt"]),
    ],
)
data class UploadedMediaRegistryEntity(
    @PrimaryKey
    val stableKey: String,
    val remotePath: String,
    val lastKnownLocalUri: String? = null,
    val bucketId: String,
    val size: Long,
    val dateTaken: Long? = null,
    val sha256: String? = null,
    val lastSeenAt: Long,
    val uploadedAt: Long,
    val deletedRemotelyAt: Long? = null,
)

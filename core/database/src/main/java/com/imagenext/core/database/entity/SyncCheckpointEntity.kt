package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for sync continuity checkpoints.
 *
 * Tracks the last successful sync state for each folder to support
 * safe incremental sync continuation after interruptions.
 */
@Entity(tableName = "sync_checkpoints")
data class SyncCheckpointEntity(
    /** Folder path this checkpoint belongs to — unique identifier. */
    @PrimaryKey
    val folderPath: String,

    /** Timestamp of the last successful sync (epoch millis). */
    val lastSyncTimestamp: Long,

    /** Last known ETag of the folder for change detection. */
    val lastEtag: String,

    /** Current sync status as a string (maps to SyncState enum). */
    val status: String,

    /** Last categorized failure code for this folder, if any. */
    val lastErrorCode: String? = null,

    /** Last failure message for this folder, if any. */
    val lastErrorMessage: String? = null,
)

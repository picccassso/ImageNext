package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisted folder selection.
 *
 * Tracks which Nextcloud folders the user has selected for media viewing.
 * Selections persist across app restarts.
 */
@Entity(tableName = "selected_folders")
data class SelectedFolderEntity(
    /** Full WebDAV remote path — unique identifier. */
    @PrimaryKey
    val remotePath: String,

    /** Human-readable folder name for UI display. */
    val displayName: String,

    /** Timestamp when this folder was added to selection (epoch millis). */
    val addedTimestamp: Long,
)

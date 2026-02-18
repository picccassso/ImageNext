package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity representing a user-managed local album. */
@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["normalizedName"], unique = true),
    ],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val albumId: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

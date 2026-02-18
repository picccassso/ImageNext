package com.imagenext.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join table mapping manual albums to media items.
 *
 * A media item can belong to multiple albums.
 */
@Entity(
    tableName = "album_media_cross_ref",
    primaryKeys = ["albumId", "mediaRemotePath"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["mediaRemotePath"]),
    ],
)
data class AlbumMediaCrossRefEntity(
    val albumId: Long,
    val mediaRemotePath: String,
    val addedAt: Long,
)

package com.imagenext.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity

/**
 * Room database root for ImageNext.
 *
 * Contains entities for media metadata, folder selections, and sync checkpoints.
 * Version 1 — initial schema, no migrations needed yet.
 *
 * Migration policy: future schema changes should use Room's migration API
 * to preserve user data across app updates.
 */
@Database(
    entities = [
        MediaItemEntity::class,
        SelectedFolderEntity::class,
        SyncCheckpointEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun folderDao(): FolderDao

    companion object {
        private const val DATABASE_NAME = "imagenext_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton database instance.
         * Uses double-checked locking for thread safety.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { INSTANCE = it }
            }
        }
    }
}

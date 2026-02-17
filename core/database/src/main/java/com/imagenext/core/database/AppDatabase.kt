package com.imagenext.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity

/**
 * Room database root for ImageNext.
 *
 * Contains entities for media metadata, folder selections, and sync checkpoints.
 * Version 4 — adds sync checkpoint error metadata for failure diagnostics.
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
    version = 4,
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
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media_items ADD COLUMN captureTimestamp INTEGER")
                database.execSQL(
                    "ALTER TABLE media_items ADD COLUMN thumbnailStatus TEXT NOT NULL DEFAULT 'PENDING'"
                )
                database.execSQL(
                    "ALTER TABLE media_items ADD COLUMN thumbnailRetryCount INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailLastError TEXT")
                database.execSQL(
                    "UPDATE media_items " +
                        "SET thumbnailStatus = CASE " +
                        "WHEN thumbnailPath IS NOT NULL AND thumbnailPath != '' THEN 'READY' " +
                        "ELSE 'PENDING' END"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE media_items " +
                        "ADD COLUMN timelineSortKey INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "UPDATE media_items " +
                        "SET timelineSortKey = COALESCE(captureTimestamp, lastModified)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_items_timelineSortKey " +
                        "ON media_items(timelineSortKey)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_items_folderPath_timelineSortKey " +
                        "ON media_items(folderPath, timelineSortKey)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_items_thumbnailStatus_thumbnailRetryCount_timelineSortKey " +
                        "ON media_items(thumbnailStatus, thumbnailRetryCount, timelineSortKey)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE sync_checkpoints " +
                        "ADD COLUMN lastErrorCode TEXT"
                )
                database.execSQL(
                    "ALTER TABLE sync_checkpoints " +
                        "ADD COLUMN lastErrorMessage TEXT"
                )
            }
        }
    }
}

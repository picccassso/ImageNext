package com.imagenext.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.AlbumEntity
import com.imagenext.core.database.entity.AlbumMediaCrossRefEntity
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity

/**
 * Room database root for ImageNext.
 *
 * Contains entities for media metadata, folder selections, sync checkpoints, and local albums.
 * Version 6 — preserves album membership during media upserts.
 *
 * Migration policy: future schema changes should use Room's migration API
 * to preserve user data across app updates.
 */
@Database(
    entities = [
        MediaItemEntity::class,
        SelectedFolderEntity::class,
        SyncCheckpointEntity::class,
        AlbumEntity::class,
        AlbumMediaCrossRefEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun folderDao(): FolderDao
    abstract fun albumDao(): AlbumDao

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
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (" +
                        "`albumId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`normalizedName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_normalizedName` " +
                        "ON `albums` (`normalizedName`)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_media_cross_ref` (" +
                        "`albumId` INTEGER NOT NULL, " +
                        "`mediaRemotePath` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`albumId`, `mediaRemotePath`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `albums`(`albumId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`mediaRemotePath`) REFERENCES `media_items`(`remotePath`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_media_cross_ref_albumId` " +
                        "ON `album_media_cross_ref` (`albumId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_media_cross_ref_mediaRemotePath` " +
                        "ON `album_media_cross_ref` (`mediaRemotePath`)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_media_cross_ref_new` (" +
                        "`albumId` INTEGER NOT NULL, " +
                        "`mediaRemotePath` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`albumId`, `mediaRemotePath`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `albums`(`albumId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO `album_media_cross_ref_new` (`albumId`, `mediaRemotePath`, `addedAt`) " +
                        "SELECT `albumId`, `mediaRemotePath`, `addedAt` FROM `album_media_cross_ref`"
                )
                database.execSQL("DROP TABLE `album_media_cross_ref`")
                database.execSQL("ALTER TABLE `album_media_cross_ref_new` RENAME TO `album_media_cross_ref`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_media_cross_ref_albumId` " +
                        "ON `album_media_cross_ref` (`albumId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_media_cross_ref_mediaRemotePath` " +
                        "ON `album_media_cross_ref` (`mediaRemotePath`)"
                )
            }
        }
    }
}

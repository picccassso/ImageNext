package com.imagenext.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.LocalBackupAlbumDao
import com.imagenext.core.database.dao.LocalBackupFolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.dao.UploadQueueDao
import com.imagenext.core.database.dao.UploadedMediaRegistryDao
import com.imagenext.core.database.entity.AlbumEntity
import com.imagenext.core.database.entity.AlbumMediaCrossRefEntity
import com.imagenext.core.database.entity.LocalBackupAlbumEntity
import com.imagenext.core.database.entity.LocalBackupFolderEntity
import com.imagenext.core.database.entity.MediaItemEntity
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.database.entity.SyncCheckpointEntity
import com.imagenext.core.database.entity.UploadQueueEntity
import com.imagenext.core.database.entity.UploadedMediaRegistryEntity

/**
 * Room database root for ImageNext.
 *
 * Contains entities for media metadata, folder selections, sync checkpoints, and local albums.
 * Version 9 — adds Nextcloud fileId tracking on media items for preview APIs.
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
        UploadQueueEntity::class,
        UploadedMediaRegistryEntity::class,
        LocalBackupAlbumEntity::class,
        LocalBackupFolderEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun folderDao(): FolderDao
    abstract fun albumDao(): AlbumDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun uploadedMediaRegistryDao(): UploadedMediaRegistryDao
    abstract fun localBackupAlbumDao(): LocalBackupAlbumDao
    abstract fun localBackupFolderDao(): LocalBackupFolderDao

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
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    .addMigrations(MIGRATION_8_9)
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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`stableKey` TEXT NOT NULL, " +
                        "`operation` TEXT NOT NULL DEFAULT 'UPLOAD', " +
                        "`localUri` TEXT, " +
                        "`mimeType` TEXT, " +
                        "`size` INTEGER, " +
                        "`dateTaken` INTEGER, " +
                        "`targetRemoteFolder` TEXT NOT NULL, " +
                        "`targetFileName` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'PENDING', " +
                        "`retryCount` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastError` TEXT, " +
                        "`lastAttemptAt` INTEGER, " +
                        "`bytesTotal` INTEGER, " +
                        "`bytesUploaded` INTEGER, " +
                        "`remotePath` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER NOT NULL DEFAULT 0)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_queue_stableKey_operation` " +
                        "ON `upload_queue` (`stableKey`, `operation`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_upload_queue_status_nextAttemptAt` " +
                        "ON `upload_queue` (`status`, `nextAttemptAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_upload_queue_createdAt` " +
                        "ON `upload_queue` (`createdAt`)"
                )

                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uploaded_media_registry` (" +
                        "`stableKey` TEXT NOT NULL, " +
                        "`remotePath` TEXT NOT NULL, " +
                        "`lastKnownLocalUri` TEXT, " +
                        "`bucketId` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`dateTaken` INTEGER, " +
                        "`sha256` TEXT, " +
                        "`lastSeenAt` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER NOT NULL, " +
                        "`deletedRemotelyAt` INTEGER, " +
                        "PRIMARY KEY(`stableKey`))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_uploaded_media_registry_remotePath` " +
                        "ON `uploaded_media_registry` (`remotePath`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_uploaded_media_registry_bucketId` " +
                        "ON `uploaded_media_registry` (`bucketId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_uploaded_media_registry_deletedRemotelyAt` " +
                        "ON `uploaded_media_registry` (`deletedRemotelyAt`)"
                )

                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_backup_albums` (" +
                        "`bucketId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bucketId`))"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_backup_folders` (" +
                        "`treeUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`treeUri`))"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE media_items ADD COLUMN fileId INTEGER"
                )
            }
        }
    }
}

package com.imagenext.core.model

/** Backup scheduling mode. */
enum class BackupSyncMode {
    MANUAL_ONLY,
    SCHEDULED,
    WHEN_CHARGING,
}

/** Scheduled-mode cadence type. */
enum class BackupScheduleType {
    INTERVAL_HOURS,
    DAILY_TIME,
}

/** Network gate for upload work. */
enum class BackupNetworkPolicy {
    WIFI_ONLY,
    WIFI_OR_MOBILE,
}

/** Additional power gating for upload work. */
enum class BackupPowerPolicy {
    REQUIRE_CHARGING,
    REQUIRE_DEVICE_IDLE,
    NONE,
}

/** Remote delete behavior for removed local items. */
enum class BackupDeletePolicy {
    APPEND_ONLY,
    MIRROR_DELETE,
}

/** Source scope for local backup discovery. */
enum class BackupSourceScope {
    FULL_LIBRARY,
    SELECTED_FOLDERS,
}

/** Upload folder layout under the selected backup destination. */
enum class BackupUploadStructure {
    FLAT_FOLDER,
    YEAR_MONTH_FOLDERS,
}

/** Media type toggles for upload selection. */
data class BackupMediaTypes(
    val uploadPhotos: Boolean = true,
    val uploadVideos: Boolean = true,
)

/** Persisted backup policy. */
data class BackupPolicy(
    val enabled: Boolean = false,
    val backupRoot: String = "/Photos/ImageNext Backup",
    val backupRootSelectedByUser: Boolean = false,
    val syncMode: BackupSyncMode = BackupSyncMode.MANUAL_ONLY,
    val scheduleType: BackupScheduleType = BackupScheduleType.INTERVAL_HOURS,
    val scheduleIntervalHours: Int = 24,
    val dailyHour: Int = 2,
    val dailyMinute: Int = 0,
    val networkPolicy: BackupNetworkPolicy = BackupNetworkPolicy.WIFI_ONLY,
    val allowRoaming: Boolean = false,
    val powerPolicy: BackupPowerPolicy = BackupPowerPolicy.REQUIRE_CHARGING,
    val mediaTypes: BackupMediaTypes = BackupMediaTypes(),
    val autoUploadNewMedia: Boolean = true,
    val deletePolicy: BackupDeletePolicy = BackupDeletePolicy.APPEND_ONLY,
    val sourceScope: BackupSourceScope = BackupSourceScope.FULL_LIBRARY,
    val uploadStructure: BackupUploadStructure = BackupUploadStructure.YEAR_MONTH_FOLDERS,
    val autoSelectBackupRoot: Boolean = true,
)

/** Upload queue operation kind. */
enum class UploadOperation {
    UPLOAD,
    DELETE,
}

/** Upload queue status. */
enum class UploadStatus {
    PENDING,
    UPLOADING,
    FAILED,
    DONE,
}

/** High-level state for backup pipeline monitoring in UI. */
enum class BackupRunState {
    IDLE,
    RUNNING,
    FAILED,
    COMPLETED,
}

/** Aggregate backup state exposed to settings UI. */
data class BackupSyncState(
    val runState: BackupRunState = BackupRunState.IDLE,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val uploadingCount: Int = 0,
    val totalQueueCount: Int = 0,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val lastError: String? = null,
    val hasLastRun: Boolean = false,
    val lastRunResult: BackupRunState = BackupRunState.IDLE,
    val lastRunUploadedCount: Int = 0,
    val lastRunSkippedCount: Int = 0,
    val lastRunDeletedCount: Int = 0,
    val lastRunFailedCount: Int = 0,
    val lastRunProcessedCount: Int = 0,
    val lastRunFinishedAt: Long? = null,
)

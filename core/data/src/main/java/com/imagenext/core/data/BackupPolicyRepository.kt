package com.imagenext.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imagenext.core.model.BackupDeletePolicy
import com.imagenext.core.model.BackupMediaTypes
import com.imagenext.core.model.BackupNetworkPolicy
import com.imagenext.core.model.BackupPolicy
import com.imagenext.core.model.BackupPowerPolicy
import com.imagenext.core.model.BackupScheduleType
import com.imagenext.core.model.BackupSourceScope
import com.imagenext.core.model.BackupSyncMode
import com.imagenext.core.model.BackupUploadStructure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME = "backup_policy"
private val Context.backupPolicyDataStore by preferencesDataStore(name = DATASTORE_NAME)

/**
 * DataStore-backed persistence for user backup policy.
 */
class BackupPolicyRepository(
    private val context: Context,
) {

    val policyFlow: Flow<BackupPolicy> = context.backupPolicyDataStore.data
        .map(::backupPolicyFromPreferences)

    suspend fun getPolicy(): BackupPolicy = policyFlow.first()

    suspend fun savePolicy(policy: BackupPolicy) {
        context.backupPolicyDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = policy.enabled
            prefs[KEY_BACKUP_ROOT] = normalizeBackupRemotePath(policy.backupRoot)
            prefs[KEY_BACKUP_ROOT_SELECTED] = policy.backupRootSelectedByUser
            prefs[KEY_SYNC_MODE] = policy.syncMode.name
            prefs[KEY_SCHEDULE_TYPE] = policy.scheduleType.name
            prefs[KEY_SCHEDULE_INTERVAL_HOURS] = sanitizeBackupIntervalHours(policy.scheduleIntervalHours)
            prefs[KEY_DAILY_HOUR] = policy.dailyHour.coerceIn(0, 23)
            prefs[KEY_DAILY_MINUTE] = policy.dailyMinute.coerceIn(0, 59)
            prefs[KEY_NETWORK_POLICY] = policy.networkPolicy.name
            prefs[KEY_ALLOW_ROAMING] = policy.allowRoaming
            prefs[KEY_POWER_POLICY] = policy.powerPolicy.name
            prefs[KEY_UPLOAD_PHOTOS] = policy.mediaTypes.uploadPhotos
            prefs[KEY_UPLOAD_VIDEOS] = policy.mediaTypes.uploadVideos
            prefs[KEY_AUTO_UPLOAD] = policy.autoUploadNewMedia
            prefs[KEY_DELETE_POLICY] = policy.deletePolicy.name
            prefs[KEY_SOURCE_SCOPE] = policy.sourceScope.name
            prefs[KEY_UPLOAD_STRUCTURE] = policy.uploadStructure.name
            prefs[KEY_AUTO_SELECT_BACKUP_ROOT] = policy.autoSelectBackupRoot
        }
    }

    suspend fun update(transform: (BackupPolicy) -> BackupPolicy) {
        context.backupPolicyDataStore.edit { prefs ->
            val updated = transform(backupPolicyFromPreferences(prefs))
            prefs[KEY_ENABLED] = updated.enabled
            prefs[KEY_BACKUP_ROOT] = normalizeBackupRemotePath(updated.backupRoot)
            prefs[KEY_BACKUP_ROOT_SELECTED] = updated.backupRootSelectedByUser
            prefs[KEY_SYNC_MODE] = updated.syncMode.name
            prefs[KEY_SCHEDULE_TYPE] = updated.scheduleType.name
            prefs[KEY_SCHEDULE_INTERVAL_HOURS] = sanitizeBackupIntervalHours(updated.scheduleIntervalHours)
            prefs[KEY_DAILY_HOUR] = updated.dailyHour.coerceIn(0, 23)
            prefs[KEY_DAILY_MINUTE] = updated.dailyMinute.coerceIn(0, 59)
            prefs[KEY_NETWORK_POLICY] = updated.networkPolicy.name
            prefs[KEY_ALLOW_ROAMING] = updated.allowRoaming
            prefs[KEY_POWER_POLICY] = updated.powerPolicy.name
            prefs[KEY_UPLOAD_PHOTOS] = updated.mediaTypes.uploadPhotos
            prefs[KEY_UPLOAD_VIDEOS] = updated.mediaTypes.uploadVideos
            prefs[KEY_AUTO_UPLOAD] = updated.autoUploadNewMedia
            prefs[KEY_DELETE_POLICY] = updated.deletePolicy.name
            prefs[KEY_SOURCE_SCOPE] = updated.sourceScope.name
            prefs[KEY_UPLOAD_STRUCTURE] = updated.uploadStructure.name
            prefs[KEY_AUTO_SELECT_BACKUP_ROOT] = updated.autoSelectBackupRoot
        }
    }

    companion object {
        internal val KEY_ENABLED = booleanPreferencesKey("enabled")
        internal val KEY_BACKUP_ROOT = stringPreferencesKey("backup_root")
        internal val KEY_BACKUP_ROOT_SELECTED = booleanPreferencesKey("backup_root_selected")
        internal val KEY_SYNC_MODE = stringPreferencesKey("sync_mode")
        internal val KEY_SCHEDULE_TYPE = stringPreferencesKey("schedule_type")
        internal val KEY_SCHEDULE_INTERVAL_HOURS = intPreferencesKey("schedule_interval_hours")
        internal val KEY_DAILY_HOUR = intPreferencesKey("daily_hour")
        internal val KEY_DAILY_MINUTE = intPreferencesKey("daily_minute")
        internal val KEY_NETWORK_POLICY = stringPreferencesKey("network_policy")
        internal val KEY_ALLOW_ROAMING = booleanPreferencesKey("allow_roaming")
        internal val KEY_POWER_POLICY = stringPreferencesKey("power_policy")
        internal val KEY_UPLOAD_PHOTOS = booleanPreferencesKey("upload_photos")
        internal val KEY_UPLOAD_VIDEOS = booleanPreferencesKey("upload_videos")
        internal val KEY_AUTO_UPLOAD = booleanPreferencesKey("auto_upload_new_media")
        internal val KEY_DELETE_POLICY = stringPreferencesKey("delete_policy")
        internal val KEY_SOURCE_SCOPE = stringPreferencesKey("source_scope")
        internal val KEY_UPLOAD_STRUCTURE = stringPreferencesKey("upload_structure")
        internal val KEY_AUTO_SELECT_BACKUP_ROOT = booleanPreferencesKey("auto_select_backup_root")
    }
}

internal fun backupPolicyFromPreferences(prefs: Preferences): BackupPolicy {
    val defaults = BackupPolicy()
    return BackupPolicy(
        enabled = prefs[BackupPolicyRepository.KEY_ENABLED] ?: defaults.enabled,
        backupRoot = normalizeBackupRemotePath(prefs[BackupPolicyRepository.KEY_BACKUP_ROOT] ?: defaults.backupRoot),
        backupRootSelectedByUser = prefs[BackupPolicyRepository.KEY_BACKUP_ROOT_SELECTED]
            ?: defaults.backupRootSelectedByUser,
        syncMode = enumOrDefault(prefs[BackupPolicyRepository.KEY_SYNC_MODE], defaults.syncMode),
        scheduleType = enumOrDefault(prefs[BackupPolicyRepository.KEY_SCHEDULE_TYPE], defaults.scheduleType),
        scheduleIntervalHours = sanitizeBackupIntervalHours(
            prefs[BackupPolicyRepository.KEY_SCHEDULE_INTERVAL_HOURS] ?: defaults.scheduleIntervalHours,
        ),
        dailyHour = (prefs[BackupPolicyRepository.KEY_DAILY_HOUR] ?: defaults.dailyHour).coerceIn(0, 23),
        dailyMinute = (prefs[BackupPolicyRepository.KEY_DAILY_MINUTE] ?: defaults.dailyMinute).coerceIn(0, 59),
        networkPolicy = enumOrDefault(prefs[BackupPolicyRepository.KEY_NETWORK_POLICY], defaults.networkPolicy),
        allowRoaming = prefs[BackupPolicyRepository.KEY_ALLOW_ROAMING] ?: defaults.allowRoaming,
        powerPolicy = enumOrDefault(prefs[BackupPolicyRepository.KEY_POWER_POLICY], defaults.powerPolicy),
        mediaTypes = BackupMediaTypes(
            uploadPhotos = prefs[BackupPolicyRepository.KEY_UPLOAD_PHOTOS] ?: defaults.mediaTypes.uploadPhotos,
            uploadVideos = prefs[BackupPolicyRepository.KEY_UPLOAD_VIDEOS] ?: defaults.mediaTypes.uploadVideos,
        ),
        autoUploadNewMedia = prefs[BackupPolicyRepository.KEY_AUTO_UPLOAD] ?: defaults.autoUploadNewMedia,
        deletePolicy = enumOrDefault(prefs[BackupPolicyRepository.KEY_DELETE_POLICY], defaults.deletePolicy),
        sourceScope = enumOrDefault(prefs[BackupPolicyRepository.KEY_SOURCE_SCOPE], defaults.sourceScope),
        uploadStructure = enumOrDefault(
            prefs[BackupPolicyRepository.KEY_UPLOAD_STRUCTURE],
            defaults.uploadStructure,
        ),
        autoSelectBackupRoot = prefs[BackupPolicyRepository.KEY_AUTO_SELECT_BACKUP_ROOT] ?: defaults.autoSelectBackupRoot,
    )
}

internal fun sanitizeBackupIntervalHours(value: Int): Int {
    return when (value) {
        2, 4, 6, 12, 24 -> value
        else -> 24
    }
}

internal fun normalizeBackupRemotePath(path: String, fallback: String = BackupPolicy().backupRoot): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return fallback
    val prefixed = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return prefixed.replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T {
    if (raw.isNullOrBlank()) return fallback
    return try {
        enumValueOf<T>(raw)
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

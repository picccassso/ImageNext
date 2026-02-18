package com.imagenext.core.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.imagenext.core.model.BackupUploadStructure
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPolicyRepositoryTest {

    @Test
    fun `backupPolicyFromPreferences restores upload structure and canonical root`() {
        val prefs = mutablePreferencesOf(
            BackupPolicyRepository.KEY_BACKUP_ROOT to "Photos//Trips/2026/",
            BackupPolicyRepository.KEY_BACKUP_ROOT_SELECTED to true,
            BackupPolicyRepository.KEY_UPLOAD_STRUCTURE to BackupUploadStructure.FLAT_FOLDER.name,
        )

        val policy = backupPolicyFromPreferences(prefs)

        assertEquals("/Photos/Trips/2026", policy.backupRoot)
        assertEquals(true, policy.backupRootSelectedByUser)
        assertEquals(BackupUploadStructure.FLAT_FOLDER, policy.uploadStructure)
    }

    @Test
    fun `backupPolicyFromPreferences falls back for invalid upload structure`() {
        val prefs = mutablePreferencesOf(
            BackupPolicyRepository.KEY_UPLOAD_STRUCTURE to "NOT_A_REAL_VALUE",
        )

        val policy = backupPolicyFromPreferences(prefs)

        assertEquals(BackupUploadStructure.YEAR_MONTH_FOLDERS, policy.uploadStructure)
    }

    @Test
    fun `normalizeBackupRemotePath normalizes separators and leading slash`() {
        val normalized = normalizeBackupRemotePath("Photos///Camera//")
        assertEquals("/Photos/Camera", normalized)
    }

    @Test
    fun `backup root selection defaults to false when not present`() {
        val policy = backupPolicyFromPreferences(mutablePreferencesOf())
        assertEquals(false, policy.backupRootSelectedByUser)
    }
}

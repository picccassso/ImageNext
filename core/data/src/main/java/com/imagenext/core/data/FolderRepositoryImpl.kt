package com.imagenext.core.data

import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.entity.SelectedFolderEntity
import com.imagenext.core.model.SelectedFolder
import com.imagenext.core.network.webdav.WebDavClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository implementation for folder discovery and selection.
 *
 * Bridges the WebDAV client (network) and Room database (local persistence)
 * for folder enumeration and user selection management.
 */
class FolderRepositoryImpl(
    private val webDavClient: WebDavClient,
    private val folderDao: FolderDao,
) {

    /**
     * Discovers available folders on the Nextcloud server.
     *
     * @return List of discoverable folders, or an error result.
     */
    fun discoverFolders(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): WebDavClient.WebDavResult<List<SelectedFolder>> {
        return webDavClient.discoverFolders(
            serverUrl = serverUrl,
            loginName = loginName,
            appPassword = appPassword,
        )
    }

    /** Observes the currently selected folders as a reactive stream. */
    fun getSelectedFolders(): Flow<List<SelectedFolder>> {
        return folderDao.getSelectedFolders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /** Returns selected folders synchronously (for sync workers). */
    suspend fun getSelectedFoldersList(): List<SelectedFolder> {
        return folderDao.getSelectedFoldersList().map { it.toDomainModel() }
    }

    /** Adds a folder to the selection and persists it. */
    suspend fun addFolder(folder: SelectedFolder) {
        folderDao.insert(
            SelectedFolderEntity(
                remotePath = folder.remotePath,
                displayName = folder.displayName,
                addedTimestamp = System.currentTimeMillis(),
            )
        )
    }

    /** Removes a folder from the selection. */
    suspend fun removeFolder(remotePath: String) {
        folderDao.delete(remotePath)
    }

    /** Returns the count of selected folders. */
    suspend fun getSelectedCount(): Int {
        return folderDao.getSelectedCount()
    }

    private fun SelectedFolderEntity.toDomainModel(): SelectedFolder {
        return SelectedFolder(
            remotePath = remotePath,
            displayName = displayName,
        )
    }
}

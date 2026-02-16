package com.imagenext.app

import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.security.SessionRepository

/**
 * Startup routing logic — determines whether the app should
 * launch into the onboarding flow, folder selection, or the main app shell.
 *
 * Decision is based on the presence of a valid persisted session
 * and whether folders have been selected.
 */
class AppStartRouter(
    private val sessionRepository: SessionRepository,
    private val folderRepository: FolderRepositoryImpl? = null,
) {

    /** App route destinations. */
    enum class StartDestination {
        ONBOARDING,
        FOLDER_SELECTION,
        MAIN,
    }

    /**
     * Determines the start destination based on current session state
     * and folder selection state.
     *
     * @return [StartDestination.MAIN] if session is valid and folders are selected,
     *         [StartDestination.FOLDER_SELECTION] if session is valid but no folders selected,
     *         [StartDestination.ONBOARDING] otherwise.
     */
    suspend fun resolveStartDestination(): StartDestination {
        if (!sessionRepository.hasValidSession()) {
            return StartDestination.ONBOARDING
        }

        // Check if folders have been selected
        val folderCount = folderRepository?.getSelectedCount() ?: 0
        return if (folderCount > 0) {
            StartDestination.MAIN
        } else {
            StartDestination.FOLDER_SELECTION
        }
    }
}

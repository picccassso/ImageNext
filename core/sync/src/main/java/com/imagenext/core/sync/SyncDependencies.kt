package com.imagenext.core.sync

import android.content.Context
import com.imagenext.core.security.SessionRepository

/**
 * Provides sync worker dependencies without a DI framework.
 *
 * Workers are constructed by WorkManager and cannot receive constructor
 * parameters. This object allows workers to retrieve dependencies
 * from the application context.
 */
object SyncDependencies {

    @Volatile
    private var sessionRepository: SessionRepository? = null

    /**
     * Initializes sync dependencies. Must be called from Application.onCreate().
     */
    fun init(sessionRepository: SessionRepository) {
        this.sessionRepository = sessionRepository
    }

    /**
     * Returns the session repository, or null if not initialized.
     */
    fun getSessionRepository(context: Context): SessionRepository? {
        return sessionRepository
    }
}

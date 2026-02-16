package com.imagenext.app

import android.app.Application
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.data.MediaRepositoryImpl
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.network.auth.LoginFlowClient
import com.imagenext.core.network.auth.NextcloudAuthApi
import com.imagenext.core.network.webdav.WebDavClient
import com.imagenext.core.security.CredentialVault
import com.imagenext.core.security.SessionRepository
import com.imagenext.core.security.SessionRepositoryImpl
import com.imagenext.core.sync.SyncDependencies
import com.imagenext.core.sync.SyncOrchestrator

/**
 * Application entrypoint.
 *
 * Provides manually-constructed dependencies for the app.
 * No DI framework is used to keep the dependency footprint minimal.
 */
class ImageNextApplication : Application() {

    /** Keystore-backed credential vault. */
    lateinit var credentialVault: CredentialVault
        private set

    /** Session repository for auth state management. */
    lateinit var sessionRepository: SessionRepository
        private set

    /** Nextcloud auth API client. */
    lateinit var authApi: NextcloudAuthApi
        private set

    /** Login Flow v2 client. */
    lateinit var loginFlowClient: LoginFlowClient
        private set

    /** App start router for onboarding/main shell decision. */
    lateinit var appStartRouter: AppStartRouter
        private set

    /** Room database instance. */
    lateinit var database: AppDatabase
        private set

    /** WebDAV client for folder discovery and media listing. */
    lateinit var webDavClient: WebDavClient
        private set

    /** Folder DAO for folder selection persistence. */
    lateinit var folderDao: FolderDao
        private set

    /** Media DAO for media metadata queries. */
    lateinit var mediaDao: MediaDao
        private set

    /** Folder repository for discovery and selection management. */
    lateinit var folderRepository: FolderRepositoryImpl
        private set

    /** Media repository for timeline and viewer access. */
    lateinit var mediaRepository: MediaRepositoryImpl
        private set

    /** Sync orchestrator for background indexing and thumbnail work. */
    lateinit var syncOrchestrator: SyncOrchestrator
        private set

    /** Timeline repository for paged Photos tab queries. */
    lateinit var timelineRepository: TimelineRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Phase 2 dependencies
        credentialVault = CredentialVault(this)
        sessionRepository = SessionRepositoryImpl(credentialVault)
        authApi = NextcloudAuthApi()
        loginFlowClient = LoginFlowClient()

        // Phase 3 dependencies
        database = AppDatabase.getInstance(this)
        folderDao = database.folderDao()
        mediaDao = database.mediaDao()
        webDavClient = WebDavClient()
        folderRepository = FolderRepositoryImpl(webDavClient, folderDao)
        mediaRepository = MediaRepositoryImpl(mediaDao)
        timelineRepository = TimelineRepository(mediaDao)
        syncOrchestrator = SyncOrchestrator(this)

        // Router needs both session and folder repository
        appStartRouter = AppStartRouter(sessionRepository, folderRepository)

        // Initialize sync worker dependencies
        SyncDependencies.init(sessionRepository)
    }
}

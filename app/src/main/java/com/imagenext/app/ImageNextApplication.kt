package com.imagenext.app

import android.app.Application
import com.imagenext.core.data.AlbumRepository
import com.imagenext.core.data.BackupPolicyRepository
import com.imagenext.core.data.FolderRepositoryImpl
import com.imagenext.core.data.LocalMediaDetector
import com.imagenext.core.data.MediaRepositoryImpl
import com.imagenext.core.data.TimelineRepository
import com.imagenext.core.data.ViewerRepository
import com.imagenext.core.database.AppDatabase
import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.FolderDao
import com.imagenext.core.database.dao.LocalBackupAlbumDao
import com.imagenext.core.database.dao.LocalBackupFolderDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.dao.UploadQueueDao
import com.imagenext.core.network.auth.LoginFlowClient
import com.imagenext.core.network.auth.NextcloudAuthApi
import com.imagenext.core.network.webdav.WebDavClient
import com.imagenext.core.security.CertificateTrustStore
import com.imagenext.core.security.CredentialVault
import com.imagenext.core.security.SessionRepository
import com.imagenext.core.security.SessionRepositoryImpl
import com.imagenext.core.security.AppLockManager
import com.imagenext.core.sync.SyncDependencies
import com.imagenext.core.sync.MediaUploadWorker
import com.imagenext.core.sync.SyncOrchestrator
import kotlinx.coroutines.runBlocking

/**
 * Application entrypoint.
 *
 * Provides manually-constructed dependencies for the app.
 * No DI framework is used to keep the dependency footprint minimal.
 */
class ImageNextApplication : Application() {

    /** Keystore-backed credential vault. */
    val credentialVault: CredentialVault by lazy {
        CredentialVault(this)
    }

    /** Session repository for auth state management. */
    val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(credentialVault)
    }

    /** Nextcloud auth API client. */
    val authApi: NextcloudAuthApi by lazy {
        NextcloudAuthApi()
    }

    /** Login Flow v2 client. */
    val loginFlowClient: LoginFlowClient by lazy {
        LoginFlowClient()
    }

    /** App start router for onboarding/main shell decision. */
    val appStartRouter: AppStartRouter by lazy {
        AppStartRouter(
            sessionRepository = sessionRepository,
            folderDao = folderDao,
        )
    }

    /** Room database instance. */
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    /** WebDAV client for folder discovery and media listing. */
    val webDavClient: WebDavClient by lazy {
        WebDavClient()
    }

    /** Folder DAO for folder selection persistence. */
    val folderDao: FolderDao by lazy {
        database.folderDao()
    }

    /** Album DAO for manual album persistence. */
    val albumDao: AlbumDao by lazy {
        database.albumDao()
    }

    /** Media DAO for media metadata queries. */
    val mediaDao: MediaDao by lazy {
        database.mediaDao()
    }

    /** Upload queue DAO for backup pipeline. */
    val uploadQueueDao: UploadQueueDao by lazy {
        database.uploadQueueDao()
    }

    /** Local backup album selection DAO. */
    val localBackupAlbumDao: LocalBackupAlbumDao by lazy {
        database.localBackupAlbumDao()
    }

    /** Local backup folder selection DAO (SAF tree URIs). */
    val localBackupFolderDao: LocalBackupFolderDao by lazy {
        database.localBackupFolderDao()
    }

    /** Folder repository for discovery and selection management. */
    val folderRepository: FolderRepositoryImpl by lazy {
        FolderRepositoryImpl(webDavClient, folderDao)
    }

    /** Media repository for timeline and viewer access. */
    val mediaRepository: MediaRepositoryImpl by lazy {
        MediaRepositoryImpl(mediaDao)
    }

    /** Manual album repository for albums and album membership operations. */
    val albumRepository: AlbumRepository by lazy {
        AlbumRepository(
            albumDao = albumDao,
            mediaDao = mediaDao,
        )
    }

    /** Sync orchestrator for background indexing and thumbnail work. */
    val syncOrchestrator: SyncOrchestrator by lazy {
        SyncOrchestrator(this)
    }

    /** Timeline repository for paged Photos tab queries. */
    val timelineRepository: TimelineRepository by lazy {
        TimelineRepository(mediaDao)
    }

    /** Backup policy repository. */
    val backupPolicyRepository: BackupPolicyRepository by lazy {
        BackupPolicyRepository(this)
    }

    /** MediaStore detector for local backup discovery. */
    val localMediaDetector: LocalMediaDetector by lazy {
        LocalMediaDetector(this)
    }

    /** Viewer repository for fullscreen viewer data access. */
    val viewerRepository: ViewerRepository by lazy {
        ViewerRepository(
            mediaDao = mediaDao,
            sessionRepository = sessionRepository,
        )
    }

    /** Certificate trust store for self-signed server support. */
    val certificateTrustStore: CertificateTrustStore by lazy {
        CertificateTrustStore(this)
    }

    /** App lock policy manager. */
    val appLockManager: AppLockManager by lazy {
        AppLockManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        SyncDependencies.init(sessionRepository)
        MediaUploadWorker.ensureNotificationChannel(this)
        runBlocking {
            syncOrchestrator.applyBackupScheduling(backupPolicyRepository.getPolicy())
        }
    }
}

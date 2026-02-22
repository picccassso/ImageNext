package com.imagenext.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.imagenext.app.ImageNextApplication
import com.imagenext.designsystem.Motion
import com.imagenext.feature.albums.AlbumDetailScreen
import com.imagenext.feature.albums.AlbumDetailViewModel
import com.imagenext.feature.albums.AlbumDetailViewModelFactory
import com.imagenext.feature.albums.AlbumsScreen
import com.imagenext.feature.albums.AlbumsViewModel
import com.imagenext.feature.albums.AlbumsViewModelFactory
import com.imagenext.feature.folders.FolderSelectionScreen
import com.imagenext.feature.folders.FolderSelectionViewModel
import com.imagenext.feature.folders.FolderSelectionViewModelFactory
import com.imagenext.feature.onboarding.OnboardingScreen
import com.imagenext.feature.onboarding.OnboardingViewModel
import com.imagenext.feature.onboarding.OnboardingViewModelFactory
import com.imagenext.feature.photos.MediaOpenRequest
import com.imagenext.feature.photos.PhotosScreen
import com.imagenext.feature.photos.PhotosViewModel
import com.imagenext.feature.photos.PhotosViewModelFactory
import com.imagenext.feature.settings.SettingsScreen
import com.imagenext.feature.settings.SettingsViewModel
import com.imagenext.feature.settings.SettingsViewModelFactory
import com.imagenext.feature.viewer.ViewerScreen
import com.imagenext.feature.viewer.ViewerViewModel
import com.imagenext.feature.viewer.ViewerViewModelFactory
import java.net.URLDecoder
import java.net.URLEncoder

/** Route constants for navigation. */
object NavRoutes {
    const val ONBOARDING = "onboarding"
    const val FOLDER_SELECTION = "folder_selection"
    const val PHOTOS = "photos"
    const val ALBUMS = "albums"
    const val ALBUM_DETAIL = "album_detail/{albumId}"
    const val SETTINGS = "settings"
    const val ALBUM_ID = "albumId"
    private const val ORIGIN_LEFT = "originLeft"
    private const val ORIGIN_TOP = "originTop"
    private const val ORIGIN_WIDTH = "originWidth"
    private const val ORIGIN_HEIGHT = "originHeight"
    const val VIEWER =
        "viewer/{remotePath}?$ORIGIN_LEFT={$ORIGIN_LEFT}&$ORIGIN_TOP={$ORIGIN_TOP}" +
            "&$ORIGIN_WIDTH={$ORIGIN_WIDTH}&$ORIGIN_HEIGHT={$ORIGIN_HEIGHT}" +
            "&$ALBUM_ID={$ALBUM_ID}"

    /** Builds album detail route for a specific album id. */
    fun albumDetailRoute(albumId: Long): String = "album_detail/$albumId"

    /** Builds a viewer route for a specific media item. */
    fun viewerRoute(request: MediaOpenRequest): String {
        val encoded = URLEncoder.encode(request.remotePath, "UTF-8")
        val origin = request.originBounds
        val left = origin?.left ?: -1
        val top = origin?.top ?: -1
        val width = origin?.width ?: -1
        val height = origin?.height ?: -1
        val albumId = request.albumId ?: -1
        return "viewer/$encoded?$ORIGIN_LEFT=$left&$ORIGIN_TOP=$top&$ORIGIN_WIDTH=$width&$ORIGIN_HEIGHT=$height&$ALBUM_ID=$albumId"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    app: ImageNextApplication,
    onboardingViewModelFactory: OnboardingViewModelFactory,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(250, easing = Motion.Easing.Emphasized))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(250, easing = Motion.Easing.Emphasized))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200))
        }
    ) {
        composable(
            route = NavRoutes.VIEWER,
            arguments = listOf(
                navArgument("remotePath") { type = NavType.StringType },
                navArgument("originLeft") { type = NavType.IntType; defaultValue = -1 },
                navArgument("originTop") { type = NavType.IntType; defaultValue = -1 },
                navArgument("originWidth") { type = NavType.IntType; defaultValue = -1 },
                navArgument("originHeight") { type = NavType.IntType; defaultValue = -1 },
                navArgument("albumId") { type = NavType.LongType; defaultValue = -1L },
            ),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("remotePath") ?: ""
            val remotePath = URLDecoder.decode(encodedPath, "UTF-8")
            val albumIdArg = backStackEntry.arguments?.getLong("albumId") ?: -1L
            val albumId = albumIdArg.takeIf { it > -1L }

            val viewerViewModel: ViewerViewModel = viewModel(
                factory = ViewerViewModelFactory(
                    viewerRepository = app.viewerRepository,
                    initialRemotePath = remotePath,
                    albumId = albumId,
                ),
            )

            ViewerScreen(
                viewModel = viewerViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        // Standard routes
        composable(route = NavRoutes.ONBOARDING) {
            val onboardingViewModel: OnboardingViewModel = viewModel(factory = onboardingViewModelFactory)
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onOnboardingComplete = {
                    navController.navigate(NavRoutes.FOLDER_SELECTION) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(route = NavRoutes.FOLDER_SELECTION) {
            val session = app.sessionRepository.getSession()
            if (session == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(NavRoutes.ONBOARDING) {
                        popUpTo(NavRoutes.FOLDER_SELECTION) { inclusive = true }
                    }
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val folderSelectionViewModel: FolderSelectionViewModel = viewModel(
                    factory = FolderSelectionViewModelFactory(
                        folderRepository = app.folderRepository,
                        syncOrchestrator = app.syncOrchestrator,
                        session = session,
                    ),
                )
                FolderSelectionScreen(
                    viewModel = folderSelectionViewModel,
                    onSelectionComplete = {
                        navController.navigate(NavRoutes.PHOTOS) {
                            popUpTo(NavRoutes.FOLDER_SELECTION) { inclusive = true }
                        }
                    },
                )
            }
        }

        composable(
            route = BottomNavDestination.Photos.route,
            enterTransition = { EnterTransition.None },
            popEnterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            val photosViewModel: PhotosViewModel = viewModel(
                factory = PhotosViewModelFactory(
                    appContext = app.applicationContext,
                    timelineRepository = app.timelineRepository,
                    syncOrchestrator = app.syncOrchestrator,
                    albumRepository = app.albumRepository,
                    serverReachabilityProbe = reachability@{
                        val session = app.sessionRepository.getSession()
                            ?: return@reachability false
                        kotlinx.coroutines.withTimeoutOrNull(5000L) {
                            app.authApi.validateCredentials(
                                serverUrl = session.serverUrl,
                                loginName = session.loginName,
                                appPassword = session.appPassword,
                            )
                        } is com.imagenext.core.network.auth.NextcloudAuthApi.AuthResult.Success
                    },
                ),
            )
            PhotosScreen(
                viewModel = photosViewModel,
                onMediaClick = { request ->
                    navController.navigate(NavRoutes.viewerRoute(request))
                },
            )
        }

        composable(route = BottomNavDestination.Albums.route) {
            val albumsViewModel: AlbumsViewModel = viewModel(
                factory = AlbumsViewModelFactory(
                    albumRepository = app.albumRepository,
                ),
            )
            AlbumsScreen(
                viewModel = albumsViewModel,
                onAlbumClick = { albumId -> navController.navigate(NavRoutes.albumDetailRoute(albumId)) },
            )
        }

        composable(
            route = NavRoutes.ALBUM_DETAIL,
            arguments = listOf(
                navArgument("albumId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId")
                ?: return@composable
            val detailViewModel: AlbumDetailViewModel = viewModel(
                factory = AlbumDetailViewModelFactory(
                    albumId = albumId,
                    albumRepository = app.albumRepository,
                ),
            )
            AlbumDetailScreen(
                viewModel = detailViewModel,
                onBack = { navController.popBackStack() },
                onAlbumDeleted = { navController.popBackStack() },
                onMediaClick = { remotePath, originBounds ->
                    navController.navigate(
                        NavRoutes.viewerRoute(
                            MediaOpenRequest(
                                remotePath = remotePath,
                                originBounds = originBounds,
                                albumId = albumId,
                            )
                        )
                    )
                },
            )
        }

        composable(route = BottomNavDestination.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    sessionRepository = app.sessionRepository,
                    authApi = app.authApi,
                    folderRepository = app.folderRepository,
                    syncOrchestrator = app.syncOrchestrator,
                    certificateTrustStore = app.certificateTrustStore,
                    appLockManager = app.appLockManager,
                    database = app.database,
                    backupPolicyRepository = app.backupPolicyRepository,
                    localMediaDetector = app.localMediaDetector,
                ),
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onLogout = {
                    navController.navigate(NavRoutes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}

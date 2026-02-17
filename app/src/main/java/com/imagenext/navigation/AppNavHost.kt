package com.imagenext.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.imagenext.feature.albums.AlbumsScreen
import com.imagenext.feature.albums.AlbumsViewModel
import com.imagenext.feature.albums.AlbumsViewModelFactory
import com.imagenext.feature.folders.FolderSelectionScreen
import com.imagenext.feature.folders.FolderSelectionViewModel
import com.imagenext.feature.folders.FolderSelectionViewModelFactory
import com.imagenext.feature.onboarding.OnboardingScreen
import com.imagenext.feature.onboarding.OnboardingViewModel
import com.imagenext.feature.onboarding.OnboardingViewModelFactory
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
    const val SETTINGS = "settings"
    const val VIEWER = "viewer/{remotePath}"

    /** Builds a viewer route for a specific media item. */
    fun viewerRoute(remotePath: String): String {
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        return "viewer/$encoded"
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
            slideInHorizontally(
                initialOffsetX = { it / 10 },
                animationSpec = tween(Motion.DURATION_LONG_MS, easing = Motion.Easing.Emphasized)
            ) + fadeIn(animationSpec = tween(Motion.DURATION_LONG_MS))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(Motion.DURATION_MEDIUM_MS))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 10 },
                animationSpec = tween(Motion.DURATION_LONG_MS, easing = Motion.Easing.Emphasized)
            ) + fadeIn(animationSpec = tween(Motion.DURATION_LONG_MS))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(Motion.DURATION_MEDIUM_MS))
        }
    ) {
        // ... (rest of the routes same but with localized transition tuning if needed)
        // Fullscreen viewer gets special scale transition
        composable(
            route = NavRoutes.VIEWER,
            arguments = listOf(
                navArgument("remotePath") { type = NavType.StringType },
            ),
            enterTransition = {
                scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(Motion.DURATION_LONG_MS, easing = Motion.Easing.Emphasized)
                ) + fadeIn(animationSpec = tween(Motion.DURATION_LONG_MS))
            },
            exitTransition = {
                scaleOut(
                    targetScale = 0.9f,
                    animationSpec = tween(Motion.DURATION_MEDIUM_MS, easing = Motion.Easing.Decelerate)
                ) + fadeOut(animationSpec = tween(Motion.DURATION_MEDIUM_MS))
            }
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("remotePath") ?: ""
            val remotePath = URLDecoder.decode(encodedPath, "UTF-8")

            val viewerViewModel: ViewerViewModel = viewModel(
                factory = ViewerViewModelFactory(
                    viewerRepository = app.viewerRepository,
                    initialRemotePath = remotePath,
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

        composable(route = BottomNavDestination.Photos.route) {
            val photosViewModel: PhotosViewModel = viewModel(
                factory = PhotosViewModelFactory(
                    timelineRepository = app.timelineRepository,
                    syncOrchestrator = app.syncOrchestrator,
                ),
            )
            PhotosScreen(
                viewModel = photosViewModel,
                onPhotoClick = { remotePath ->
                    navController.navigate(NavRoutes.viewerRoute(remotePath))
                },
            )
        }

        composable(route = BottomNavDestination.Albums.route) {
            val albumsViewModel: AlbumsViewModel = viewModel(
                factory = AlbumsViewModelFactory(
                    folderDao = app.folderDao,
                    mediaDao = app.mediaDao,
                ),
            )
            AlbumsScreen(
                viewModel = albumsViewModel,
                onAlbumClick = { navController.navigate(BottomNavDestination.Photos.route) },
            )
        }

        composable(route = BottomNavDestination.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    sessionRepository = app.sessionRepository,
                    folderRepository = app.folderRepository,
                    syncOrchestrator = app.syncOrchestrator,
                    certificateTrustStore = app.certificateTrustStore,
                    appLockManager = app.appLockManager,
                    database = app.database,
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

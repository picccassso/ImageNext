package com.imagenext.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.imagenext.app.ImageNextApplication
import com.imagenext.feature.albums.AlbumsScreen
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

/** Route constants for navigation. */
object NavRoutes {
    const val ONBOARDING = "onboarding"
    const val FOLDER_SELECTION = "folder_selection"
    const val PHOTOS = "photos"
    const val ALBUMS = "albums"
    const val SETTINGS = "settings"
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
    ) {
        // Onboarding flow
        composable(route = NavRoutes.ONBOARDING) {
            // viewModel() scopes the ViewModel to the Activity's ViewModelStore,
            // so it survives activity recreation during browser Login Flow v2.
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = onboardingViewModelFactory,
            )
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onOnboardingComplete = {
                    navController.navigate(NavRoutes.FOLDER_SELECTION) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        // Folder selection (post-onboarding, pre-main app)
        composable(route = NavRoutes.FOLDER_SELECTION) {
            val session = app.sessionRepository.getSession()
            if (session == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(NavRoutes.ONBOARDING) {
                        popUpTo(NavRoutes.FOLDER_SELECTION) { inclusive = true }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
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

        // Main app tabs
        composable(route = BottomNavDestination.Photos.route) {
            val photosViewModel: PhotosViewModel = viewModel(
                factory = PhotosViewModelFactory(
                    timelineRepository = app.timelineRepository,
                    syncOrchestrator = app.syncOrchestrator,
                ),
            )
            PhotosScreen(viewModel = photosViewModel)
        }
        composable(route = BottomNavDestination.Albums.route) {
            AlbumsScreen()
        }
        composable(route = BottomNavDestination.Settings.route) {
            SettingsScreen()
        }
    }
}

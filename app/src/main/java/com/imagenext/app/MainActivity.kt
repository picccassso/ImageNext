package com.imagenext.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.imagenext.designsystem.ImageNextTheme
import com.imagenext.feature.onboarding.OnboardingViewModelFactory
import com.imagenext.navigation.AppNavHost
import com.imagenext.navigation.BottomNavDestination
import com.imagenext.navigation.NavRoutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ImageNextApplication

        setContent {
            ImageNextTheme {
                // Resolve start destination asynchronously (folder count needs DB access)
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    startDestination = when (app.appStartRouter.resolveStartDestination()) {
                        AppStartRouter.StartDestination.ONBOARDING -> NavRoutes.ONBOARDING
                        AppStartRouter.StartDestination.FOLDER_SELECTION -> NavRoutes.FOLDER_SELECTION
                        AppStartRouter.StartDestination.MAIN -> NavRoutes.PHOTOS
                    }
                }

                if (startDestination != null) {
                    ImageNextApp(
                        startDestination = startDestination!!,
                        app = app,
                    )
                } else {
                    // Brief loading while resolving start destination
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { /* Empty — splash screen covers this momentarily */ }
                }
            }
        }
    }
}

@Composable
fun ImageNextApp(
    startDestination: String,
    app: ImageNextApplication,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Create OnboardingViewModel via ViewModelProvider so it survives activity
    // recreation (critical for Login Flow v2 browser handoff).
    val onboardingViewModelFactory = remember {
        OnboardingViewModelFactory(
            authApi = app.authApi,
            loginFlowClient = app.loginFlowClient,
            sessionRepository = app.sessionRepository,
        )
    }

    // Determine if we should show the bottom nav bar.
    // Hide it during onboarding and folder selection.
    val isOnboarding = currentDestination?.route == NavRoutes.ONBOARDING
    val isFolderSelection = currentDestination?.route == NavRoutes.FOLDER_SELECTION

    Scaffold(
        bottomBar = {
            if (!isOnboarding && !isFolderSelection) {
                NavigationBar {
                    BottomNavDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(text = destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            startDestination = startDestination,
            app = app,
            onboardingViewModelFactory = onboardingViewModelFactory,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

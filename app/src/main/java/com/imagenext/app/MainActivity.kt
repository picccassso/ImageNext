package com.imagenext.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.imagenext.designsystem.ImageNextTheme
import com.imagenext.feature.onboarding.OnboardingViewModelFactory
import com.imagenext.navigation.AppNavHost
import com.imagenext.navigation.BottomNavDestination
import com.imagenext.navigation.NavRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ImageNextApplication

        setContent {
            ImageNextTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val destination = withContext(Dispatchers.IO) {
                        app.appStartRouter.resolveStartDestination()
                    }
                    startDestination = when (destination) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) { /* Splash screen covers this */ }
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

    val onboardingViewModelFactory = remember {
        OnboardingViewModelFactory(
            authApi = app.authApi,
            loginFlowClient = app.loginFlowClient,
            sessionRepository = app.sessionRepository,
        )
    }

    val isOnboarding = currentDestination?.route == NavRoutes.ONBOARDING
    val isFolderSelection = currentDestination?.route == NavRoutes.FOLDER_SELECTION
    val isViewer = currentDestination?.route == NavRoutes.VIEWER
    val showBottomBar = !isOnboarding && !isFolderSelection && !isViewer

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 0.dp,
                    ) {
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
                                label = { 
                                    Text(
                                        text = destination.label,
                                        style = MaterialTheme.typography.labelSmall
                                    ) 
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    indicatorColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
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

package com.imagenext.app

import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Bundle
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.imagenext.core.security.LockMethod
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
    var isLocked by remember { mutableStateOf(app.appLockManager.isLockEnabled()) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val lifecycleOwner = LocalLifecycleOwner.current

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
    val showBottomBar = !isOnboarding && !isFolderSelection && !isViewer && !isLocked

    DisposableEffect(lifecycleOwner, app.appLockManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> app.appLockManager.onAppPaused()
                Lifecycle.Event.ON_START -> {
                    app.appLockManager.onAppResumed()
                    if (app.appLockManager.shouldShowLockOnResume()) {
                        isLocked = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
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
        Box(modifier = Modifier.fillMaxSize()) {
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
                app = app,
                onboardingViewModelFactory = onboardingViewModelFactory,
                modifier = Modifier.padding(innerPadding),
            )
            if (isLocked) {
                AppLockScreen(
                    app = app,
                    onUnlocked = {
                        app.appLockManager.onUnlocked()
                        isLocked = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppLockScreen(
    app: ImageNextApplication,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val lockMethod = app.appLockManager.getLockMethod()
    val hasPin = app.appLockManager.hasPin()
    val canUseBiometric = lockMethod == LockMethod.BIOMETRIC &&
            app.appLockManager.isBiometricAvailable() &&
            activity != null

    // Avoid deadlock if lock is enabled without any configured unlock path.
    if (!canUseBiometric && !hasPin) {
        LaunchedEffect(Unit) { onUnlocked() }
        return
    }

    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val latestOnUnlocked by rememberUpdatedState(onUnlocked)

    fun startBiometric() {
        val safeActivity = activity ?: return
        val hasBiometricPermission =
            ActivityCompat.checkSelfPermission(
                safeActivity,
                android.Manifest.permission.USE_BIOMETRIC,
            ) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                        safeActivity,
                        android.Manifest.permission.USE_FINGERPRINT,
                    ) == PackageManager.PERMISSION_GRANTED
        if (!hasBiometricPermission) {
            errorText = "Biometric permission is missing."
            return
        }
        val prompt = BiometricPrompt.Builder(safeActivity)
            .setTitle("Unlock ImageNext")
            .setSubtitle("Verify your identity")
            .setNegativeButton("Use PIN", ContextCompat.getMainExecutor(safeActivity)) { _, _ -> }
            .build()
        try {
            prompt.authenticate(
                CancellationSignal(),
                ContextCompat.getMainExecutor(safeActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        latestOnUnlocked()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        errorText = errString?.toString() ?: "Biometric authentication failed."
                    }
                },
            )
        } catch (_: SecurityException) {
            errorText = "Biometric permission is missing."
        }
    }

    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric) {
            startBiometric()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "App Locked",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (canUseBiometric) "Use biometrics or enter PIN to continue." else "Enter your PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(18.dp))

                if (hasPin) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (app.appLockManager.verifyPin(pin)) {
                                latestOnUnlocked()
                            } else {
                                errorText = "Incorrect PIN."
                            }
                        },
                        enabled = pin.length >= 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Unlock")
                    }
                }

                if (canUseBiometric) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { startBiometric() }) {
                        Text("Use biometrics")
                    }
                }

                if (!errorText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

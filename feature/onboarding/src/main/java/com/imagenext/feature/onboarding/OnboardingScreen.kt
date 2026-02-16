package com.imagenext.feature.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Main onboarding coordinator composable.
 *
 * Observes [OnboardingViewModel.state] and renders the appropriate screen
 * for each state in the onboarding flow.
 *
 * @param viewModel The onboarding state orchestrator.
 * @param onOnboardingComplete Callback invoked when auth succeeds and session is persisted.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val loginFlowUrl by viewModel.loginFlowUrl.collectAsState()
    val context = LocalContext.current

    // Launch browser for Login Flow v2 when URL becomes available
    LaunchedEffect(loginFlowUrl) {
        loginFlowUrl?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            viewModel.onLoginFlowUrlConsumed()
        }
    }

    // Navigate away when onboarding completes
    LaunchedEffect(state) {
        if (state is OnboardingState.Complete) {
            onOnboardingComplete()
        }
    }

    when (val currentState = state) {
        is OnboardingState.Welcome -> {
            WelcomeScreen(
                onGetStarted = viewModel::onGetStarted,
                modifier = modifier,
            )
        }

        is OnboardingState.ServerSetup -> {
            ServerSetupScreen(
                isConnecting = false,
                onConnect = viewModel::onServerUrlSubmitted,
                modifier = modifier,
            )
        }

        is OnboardingState.Connecting -> {
            ServerSetupScreen(
                isConnecting = true,
                onConnect = { },
                modifier = modifier,
            )
        }

        is OnboardingState.Login -> {
            LoginScreen(
                serverUrl = currentState.serverUrl,
                isAuthenticating = false,
                onBrowserLogin = viewModel::onStartBrowserLogin,
                onManualLogin = viewModel::onManualLogin,
                modifier = modifier,
            )
        }

        is OnboardingState.Authenticating -> {
            LoginScreen(
                serverUrl = currentState.serverUrl,
                isAuthenticating = true,
                onBrowserLogin = { },
                onManualLogin = { _, _ -> },
                modifier = modifier,
            )
        }

        is OnboardingState.Error -> {
            ErrorScreen(
                message = currentState.message,
                onRetry = viewModel::onRetry,
                modifier = modifier,
            )
        }

        is OnboardingState.Complete -> {
            // Handled by LaunchedEffect above — show brief loading state
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Welcome!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Error screen with user-readable message and retry button.
 */
@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Try Again")
        }
    }
}

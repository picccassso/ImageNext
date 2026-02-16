package com.imagenext.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Login screen with two authentication paths:
 *
 * 1. **Primary**: "Sign in with Browser" — launches Login Flow v2.
 * 2. **Fallback**: Manual username + app-password form.
 *
 * The manual fallback is always visible as a secondary option.
 * The screen guides users to use app-passwords (not their main password)
 * when using the manual path.
 */
@Composable
fun LoginScreen(
    serverUrl: String,
    isAuthenticating: Boolean,
    onBrowserLogin: () -> Unit,
    onManualLogin: (username: String, appPassword: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showManualForm by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var appPassword by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sign In",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = serverUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isAuthenticating) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Authenticating…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!showManualForm) {
            // Primary: Browser Login Flow v2
            Button(
                onClick = onBrowserLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Sign in with Browser")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { showManualForm = true },
            ) {
                Text(text = "Use app password instead")
            }
        } else {
            // Fallback: Manual app-password form
            Text(
                text = "Use an app password generated from your Nextcloud security settings. " +
                        "Do not use your main account password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = appPassword,
                onValueChange = { appPassword = it },
                label = { Text("App Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (username.isNotBlank() && appPassword.isNotBlank()) {
                            onManualLogin(username, appPassword)
                        }
                    }
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onManualLogin(username, appPassword) },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank() && appPassword.isNotBlank(),
            ) {
                Text(text = "Sign In")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showManualForm = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Back to browser login")
            }
        }
    }
}

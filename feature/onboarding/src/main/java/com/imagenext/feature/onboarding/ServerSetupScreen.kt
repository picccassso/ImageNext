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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Server setup screen — user enters their Nextcloud server URL.
 *
 * Provides a text field for URL input with a "Connect" button.
 * Shows a loading indicator during connectivity checks.
 */
@Composable
fun ServerSetupScreen(
    isConnecting: Boolean,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Connect to Nextcloud",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your server address to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("cloud.example.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isConnecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (serverUrl.isNotBlank() && !isConnecting) {
                        onConnect(serverUrl)
                    }
                }
            ),
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isConnecting) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onConnect(serverUrl) },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank(),
            ) {
                Text(text = "Connect")
            }
        }
    }
}

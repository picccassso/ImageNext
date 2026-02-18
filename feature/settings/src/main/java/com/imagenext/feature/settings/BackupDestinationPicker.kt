package com.imagenext.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BackupDestinationPicker(
    value: String,
    isSelectedByUser: Boolean,
    needsReselection: Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Backup destination",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (isSelectedByUser) value else "No backup destination selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = onOpenPicker,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(44.dp),
        ) {
            Text(if (needsReselection || !isSelectedByUser) "Select folder" else "Change folder")
        }
    }
}

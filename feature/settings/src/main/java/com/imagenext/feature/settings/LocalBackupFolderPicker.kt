package com.imagenext.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LocalBackupFolderPicker(
    selectedFolders: List<LocalBackupFolderOption>,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedFolders.isEmpty()) {
        Text(
            text = "No folders selected yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 8.dp),
        )
        return
    }

    selectedFolders.forEach { folder ->
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemoveFolder(folder.treeUri) }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove folder",
                )
            }
        }
    }
}

package com.imagenext.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavDestination(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    Photos(
        label = "Photos",
        icon = Icons.Filled.Photo,
        route = "photos",
    ),
    Albums(
        label = "Albums",
        icon = Icons.Filled.Collections,
        route = "albums",
    ),
    Settings(
        label = "Settings",
        icon = Icons.Filled.Settings,
        route = "settings",
    ),
}

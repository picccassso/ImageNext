package com.imagenext.feature.albums

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.imagenext.core.model.MediaItem
import java.io.File
import kotlin.math.roundToInt

private const val DETAIL_GRID_COLUMNS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    onAlbumDeleted: () -> Unit,
    onMediaClick: (remotePath: String, originBounds: IntRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectionState by viewModel.selectionState.collectAsStateWithLifecycle()
    val pagingItems = viewModel.mediaItems.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isSelectionMode = selectionState.isSelectionMode

    BackHandler(enabled = isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AlbumDetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
                AlbumDetailEvent.AlbumDeleted -> onAlbumDeleted()
            }
        }
    }

    if (!state.exists) {
        LaunchedEffect(state.exists) { onAlbumDeleted() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
        topBar = {
            AnimatedContent(
                targetState = isSelectionMode,
                transitionSpec = {
                    if (targetState) {
                        (slideInVertically { height -> -height } + fadeIn(tween(200))) togetherWith
                            (slideOutVertically { height -> -height } + fadeOut(tween(150)))
                    } else {
                        (slideInVertically { height -> -height } + fadeIn(tween(200))) togetherWith
                            (slideOutVertically { height -> -height } + fadeOut(tween(150)))
                    }
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                label = "AlbumDetailTopBarTransition",
            ) { inSelectionMode ->
                if (inSelectionMode) {
                    SelectionContextBar(
                        selectedCount = selectionState.selectedCount,
                        onClose = { viewModel.exitSelectionMode() },
                        onRemove = { viewModel.removeSelectedMedia() },
                    )
                } else {
                    TopAppBar(
                        title = { Text(state.title) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                        actions = {
                            if (!state.isSystem) {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Album options",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showRenameDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete album") },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showDeleteDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    )
                }
            }
        },
    ) { innerPadding ->
        when {
            pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            pagingItems.itemCount == 0 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (state.albumId == com.imagenext.core.data.AlbumRepository.SYSTEM_ALBUM_RECENTS_ID) {
                                "No recent items"
                            } else {
                                "No items in this album"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (state.isSystem) {
                                "This album updates automatically as your library changes."
                            } else {
                                "Long-press photos in the Photos tab and add them here."
                            },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(DETAIL_GRID_COLUMNS),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(1.5.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = { index -> pagingItems[index]?.remotePath ?: "placeholder_$index" },
                    ) { index ->
                        val mediaItem = pagingItems[index]
                        if (mediaItem == null) {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            )
                        } else {
                            val isSelected = selectionState.selectedRemotePaths.contains(mediaItem.remotePath)
                            AlbumMediaCell(
                                mediaItem = mediaItem,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = { remotePath, bounds ->
                                    if (isSelectionMode) {
                                        viewModel.onMediaTappedInSelection(remotePath)
                                    } else {
                                        onMediaClick(remotePath, bounds)
                                    }
                                },
                                onLongClick = {
                                    if (!state.isSystem) {
                                        viewModel.onMediaLongPressed(mediaItem.remotePath)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        var draft by remember(state.title) { mutableStateOf(state.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Album name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        viewModel.renameAlbum(draft)
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete album") },
            text = { Text("Delete this album? Items stay in your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAlbum()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

}

@Composable
private fun SelectionContextBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel selection",
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Button(
            onClick = onRemove,
            enabled = selectedCount > 0,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun AlbumMediaCell(
    mediaItem: MediaItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: (remotePath: String, originBounds: IntRect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cellBounds by remember(mediaItem.remotePath) { mutableStateOf<IntRect?>(null) }
    val thumbnailModel = remember(mediaItem.thumbnailPath) {
        mediaItem.thumbnailPath?.let { path ->
            val file = File(path)
            if (file.exists()) file else null
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { onClick(mediaItem.remotePath, cellBounds) },
                onLongClick = onLongClick,
            )
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                cellBounds = IntRect(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    right = bounds.right.roundToInt(),
                    bottom = bounds.bottom.roundToInt(),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailModel != null) {
            AsyncImage(
                model = thumbnailModel,
                contentDescription = mediaItem.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (mediaItem.isVideo) Icons.Default.Videocam else Icons.Default.Photo,
                contentDescription = mediaItem.fileName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.size(32.dp),
            )
        }

        if (mediaItem.isVideo && !isSelectionMode) {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
        }

        if (isSelectionMode) {
            // Smooth animated background alpha based on selection state
            val targetAlpha = if (isSelected) 0.34f else 0.16f
            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(durationMillis = 200),
                label = "SelectionOverlayAlpha",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = animatedAlpha)),
            )
            // Animated checkmark with scale + fade
            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(
                    initialScale = 0.5f,
                    animationSpec = tween(durationMillis = 200),
                ) + fadeIn(animationSpec = tween(durationMillis = 150)),
                exit = scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(durationMillis = 150),
                ) + fadeOut(animationSpec = tween(durationMillis = 100)),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        }
    }
}

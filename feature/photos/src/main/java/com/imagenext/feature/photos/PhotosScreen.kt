package com.imagenext.feature.photos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncDependencies
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.roundToInt

private const val GRID_COLUMNS = 3
private const val GRID_PREVIEW_SIZE = 256
private const val PERF_TAG = "ImageNextPerf"
private const val MAX_FLING_VELOCITY_PX_PER_SECOND = 8500f

data class MediaOpenRequest(
    val remotePath: String,
    val originBounds: IntRect? = null,
    val albumId: Long? = null,
)

@OptIn(
    androidx.compose.material.ExperimentalMaterialApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onMediaClick: (MediaOpenRequest) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenStartMs = remember { SystemClock.elapsedRealtime() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
    val gridFlingBehavior = remember(defaultFlingBehavior) {
        VelocityCappedFlingBehavior(
            delegate = defaultFlingBehavior,
            maxAbsVelocityPxPerSecond = MAX_FLING_VELOCITY_PX_PER_SECOND,
        )
    }
    val pagingItems = viewModel.timelineItems.collectAsLazyPagingItems()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val albumPickerItems by viewModel.albumPickerItems.collectAsStateWithLifecycle()
    val pendingReturnAnchor by viewModel.pendingReturnAnchor.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAlbumTarget by remember { mutableStateOf<MediaItem?>(null) }
    var createAlbumTarget by remember { mutableStateOf<MediaItem?>(null) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hasDisplayedPhotos by rememberSaveable { mutableStateOf(false) }
    val allowRemotePreview by remember(gridState) {
        derivedStateOf { !gridState.isScrollInProgress }
    }
    val remotePreviewAuth = remember(context) {
        SyncDependencies.getSessionRepository(context)
            ?.getSession()
            ?.let { session ->
                RemotePreviewAuth(
                    serverUrl = session.serverUrl,
                    authHeader = buildBasicAuthHeader(
                        username = session.loginName,
                        password = session.appPassword,
                    ),
                )
            }
    }
    val isInitialLoad =
        pagingItems.loadState.refresh is LoadState.Loading &&
            pagingItems.itemCount == 0 &&
            !hasDisplayedPhotos
    val isInitialError =
        pagingItems.loadState.refresh is LoadState.Error &&
            pagingItems.itemCount == 0 &&
            !hasDisplayedPhotos
    val isRefreshing =
        !isOffline && (
            syncState == SyncState.Running ||
                (pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount > 0)
        )
    val isSyncActive = syncState == SyncState.Running
    val canTriggerManualRefresh =
        !isSyncActive && !isOffline && pagingItems.loadState.refresh !is LoadState.Loading
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            if (!canTriggerManualRefresh) return@rememberPullRefreshState
            viewModel.refreshPhotos()
            pagingItems.refresh()
        },
    )
    var loggedInitialLoadDone by remember { mutableStateOf(false) }
    var loggedFirstItemsVisible by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.onPhotosForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pagingItems.itemCount) {
        if (pagingItems.itemCount > 0) {
            hasDisplayedPhotos = true
            if (!loggedFirstItemsVisible) {
                Log.d(PERF_TAG, "photos_first_items_visible_ms=${SystemClock.elapsedRealtime() - screenStartMs}")
                loggedFirstItemsVisible = true
            }
        }
    }

    LaunchedEffect(isInitialLoad) {
        if (!loggedInitialLoadDone && !isInitialLoad) {
            Log.d(PERF_TAG, "photos_initial_load_done_ms=${SystemClock.elapsedRealtime() - screenStartMs}")
            loggedInitialLoadDone = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(pendingReturnAnchor, pagingItems.itemCount) {
        val anchor = pendingReturnAnchor ?: return@LaunchedEffect
        if (!anchor.readyToApply) return@LaunchedEffect
        if (pagingItems.itemCount <= 0) return@LaunchedEffect

        val targetIndex = findTimelineIndexForRemotePath(pagingItems, anchor.remotePath)
        if (targetIndex < 0) return@LaunchedEffect

        val safeIndex = anchor.firstVisibleItemIndex.coerceIn(0, pagingItems.itemCount - 1)
        val safeOffset = anchor.firstVisibleItemScrollOffset.coerceAtLeast(0)
        gridState.scrollToItem(index = safeIndex, scrollOffset = safeOffset)
        viewModel.onReturnAnchorApplied()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pullRefresh(
                state = pullRefreshState,
                enabled = canTriggerManualRefresh,
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SyncStatusBar(syncState = syncState, isOffline = isOffline)

            when {
                isInitialLoad -> LoadingState()
                isInitialError -> {
                    val error = pagingItems.loadState.refresh as LoadState.Error
                    ErrorState(
                        message = error.error.localizedMessage ?: "Failed to load photos",
                        onRetry = { pagingItems.retry() },
                    )
                }
                pagingItems.loadState.refresh is LoadState.NotLoading &&
                    pagingItems.itemCount == 0 -> {
                    EmptyState(syncState = syncState)
                }
                else -> {
                    PhotosGrid(
                        pagingItems = pagingItems,
                        gridState = gridState,
                        flingBehavior = gridFlingBehavior,
                        allowRemotePreview = allowRemotePreview,
                        onMediaClick = { request ->
                            viewModel.onMediaOpened(
                                remotePath = request.remotePath,
                                firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                                firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                            )
                            onMediaClick(request)
                        },
                        onMediaLongClick = { mediaItem ->
                            pendingAlbumTarget = mediaItem
                        },
                        remotePreviewAuth = remotePreviewAuth,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    pendingAlbumTarget?.let { mediaItem ->
        AddToAlbumBottomSheet(
            albumItems = albumPickerItems,
            sheetState = bottomSheetState,
            onDismiss = { pendingAlbumTarget = null },
            onAddToAlbum = { albumId ->
                viewModel.addMediaToAlbum(albumId, mediaItem.remotePath)
                pendingAlbumTarget = null
            },
            onCreateAlbum = {
                createAlbumTarget = mediaItem
                pendingAlbumTarget = null
            },
        )
    }

    createAlbumTarget?.let { mediaItem ->
        var albumName by remember(mediaItem.remotePath) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { createAlbumTarget = null },
            title = { Text("Create album") },
            text = {
                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    label = { Text("Album name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        createAlbumTarget = null
                        viewModel.createAlbumAndAdd(albumName, mediaItem.remotePath)
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { createAlbumTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PhotosGrid(
    pagingItems: LazyPagingItems<TimelineItem>,
    gridState: LazyGridState,
    flingBehavior: FlingBehavior,
    allowRemotePreview: Boolean,
    onMediaClick: (MediaOpenRequest) -> Unit,
    onMediaLongClick: (MediaItem) -> Unit,
    remotePreviewAuth: RemotePreviewAuth?,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        flingBehavior = flingBehavior,
        modifier = modifier,
        contentPadding = PaddingValues(1.5.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        items(
            count = pagingItems.itemCount,
            key = { index ->
                when (val item = pagingItems[index]) {
                    is TimelineItem.Header -> "header_${item.date?.toEpochDay() ?: Long.MIN_VALUE}"
                    is TimelineItem.Media -> item.mediaItem.remotePath
                    null -> "placeholder_$index"
                }
            },
            contentType = { index ->
                when (pagingItems[index]) {
                    is TimelineItem.Header -> "header"
                    is TimelineItem.Media -> "media"
                    null -> "placeholder"
                }
            },
            span = { index ->
                when (pagingItems[index]) {
                    is TimelineItem.Header -> GridItemSpan(GRID_COLUMNS)
                    else -> GridItemSpan(1)
                }
            },
        ) { index ->
            when (val item = pagingItems[index]) {
                is TimelineItem.Header -> {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
                is TimelineItem.Media -> {
                    ThumbnailCell(
                        mediaItem = item.mediaItem,
                        remotePreviewAuth = remotePreviewAuth,
                        allowRemotePreview = allowRemotePreview,
                        onLongClick = { onMediaLongClick(item.mediaItem) },
                        onClick = { originBounds ->
                            onMediaClick(
                                MediaOpenRequest(
                                    remotePath = item.mediaItem.remotePath,
                                    originBounds = originBounds,
                                )
                            )
                        },
                    )
                }
                null -> {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
                }
            }
        }

        if (pagingItems.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(GRID_COLUMNS) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    mediaItem: MediaItem,
    remotePreviewAuth: RemotePreviewAuth?,
    allowRemotePreview: Boolean,
    onLongClick: () -> Unit,
    onClick: (IntRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cellBounds by remember(mediaItem.remotePath) { mutableStateOf<IntRect?>(null) }
    val thumbnailModel = remember(
        mediaItem.remotePath,
        mediaItem.thumbnailPath,
        remotePreviewAuth,
        allowRemotePreview,
    ) {
        resolveGridImageModel(
            context = context,
            mediaItem = mediaItem,
            remotePreviewAuth = remotePreviewAuth,
            allowRemotePreview = allowRemotePreview,
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                cellBounds = IntRect(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    right = bounds.right.roundToInt(),
                    bottom = bounds.bottom.roundToInt(),
                )
            }
            .combinedClickable(
                onClick = { onClick(cellBounds) },
                onLongClick = onLongClick,
            ),
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

        if (mediaItem.isVideo) {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToAlbumBottomSheet(
    albumItems: List<com.imagenext.core.data.AlbumPickerItem>,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onAddToAlbum: (Long) -> Unit,
    onCreateAlbum: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Add to album",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onCreateAlbum) {
                Text("Create new album")
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (albumItems.isEmpty()) {
                Text(
                    text = "No albums yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            } else {
                albumItems.forEach { album ->
                    TextButton(onClick = { onAddToAlbum(album.id) }) {
                        Text("${album.displayName} (${album.mediaCount})")
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Loading photos...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun EmptyState(syncState: SyncState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = if (syncState == SyncState.Running || syncState == SyncState.Partial) {
                    "Your gallery is coming to life"
                } else {
                    "Gallery is empty"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (syncState == SyncState.Running || syncState == SyncState.Partial) {
                    "We're synchronizing your library. Your photos will appear here shortly."
                } else {
                    "No photos were found in the selected folders. Try adding more folders in settings."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Connection Interrupted",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRetry,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text("Retry", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SyncStatusBar(syncState: SyncState, isOffline: Boolean) {
    AnimatedVisibility(
        visible = isOffline,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Offline",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }

    AnimatedVisibility(
        visible = !isOffline && syncState == SyncState.Running,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

private fun resolveGridImageModel(
    context: Context,
    mediaItem: MediaItem,
    remotePreviewAuth: RemotePreviewAuth?,
    allowRemotePreview: Boolean,
): Any? {
    val localThumbnail = mediaItem.thumbnailPath?.let { path ->
        val file = File(path)
        if (file.exists()) file else null
    }
    if (localThumbnail != null) return localThumbnail
    if (!allowRemotePreview) return null

    val previewUrl = buildPreviewUrl(
        serverUrl = remotePreviewAuth?.serverUrl ?: return null,
        remotePath = mediaItem.remotePath,
        fileId = mediaItem.fileId,
    )

    return ImageRequest.Builder(context)
        .data(previewUrl)
        .httpHeaders(
            NetworkHeaders.Builder()
                .set("Authorization", remotePreviewAuth.authHeader)
                .build()
        )
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()
}

private fun buildPreviewUrl(serverUrl: String, remotePath: String, fileId: Long?): String {
    val query = if (fileId != null && fileId > 0L) {
        "fileId=$fileId"
    } else {
        val encodedPath = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        "file=$encodedPath"
    }
    return "$serverUrl/index.php/core/preview?$query&x=$GRID_PREVIEW_SIZE&y=$GRID_PREVIEW_SIZE&a=1&mode=cover&forceIcon=0"
}

private fun buildBasicAuthHeader(username: String, password: String): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    return "Basic $encoded"
}

private data class RemotePreviewAuth(
    val serverUrl: String,
    val authHeader: String,
)

private fun findTimelineIndexForRemotePath(
    pagingItems: LazyPagingItems<TimelineItem>,
    remotePath: String,
): Int {
    for (index in 0 until pagingItems.itemCount) {
        val item = pagingItems.peek(index) ?: continue
        if (item is TimelineItem.Media && item.mediaItem.remotePath == remotePath) {
            return index
        }
    }
    return -1
}

private class VelocityCappedFlingBehavior(
    private val delegate: FlingBehavior,
    private val maxAbsVelocityPxPerSecond: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val cappedVelocity = initialVelocity.coerceIn(
            -maxAbsVelocityPxPerSecond,
            maxAbsVelocityPxPerSecond,
        )
        return with(delegate) { performFling(cappedVelocity) }
    }
}

package com.imagenext.feature.photos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.SyncState
import com.imagenext.core.sync.SyncDependencies
import com.imagenext.designsystem.ImageNextBlack
import com.imagenext.designsystem.Motion
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val GRID_COLUMNS = 3
private const val PERF_TAG = "ImageNextPerf"

@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onPhotoClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenStartMs = remember { SystemClock.elapsedRealtime() }
    val pagingItems = viewModel.timelineItems.collectAsLazyPagingItems()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val isInitialLoad = pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0
    val isInitialError = pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0
    var loggedInitialLoadDone by remember { mutableStateOf(false) }
    var loggedFirstItemsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isInitialLoad) {
        if (!loggedInitialLoadDone && !isInitialLoad) {
            Log.d(PERF_TAG, "photos_initial_load_done_ms=${SystemClock.elapsedRealtime() - screenStartMs}")
            loggedInitialLoadDone = true
        }
    }

    LaunchedEffect(pagingItems.itemCount) {
        if (!loggedFirstItemsVisible && pagingItems.itemCount > 0) {
            Log.d(PERF_TAG, "photos_first_items_visible_ms=${SystemClock.elapsedRealtime() - screenStartMs}")
            loggedFirstItemsVisible = true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SyncStatusBar(syncState = syncState, onRetry = { viewModel.retrySync() })

            when {
                isInitialLoad -> LoadingState()
                isInitialError -> {
                    val error = pagingItems.loadState.refresh as LoadState.Error
                    ErrorState(
                        message = error.error.localizedMessage ?: "Failed to load photos",
                        onRetry = { pagingItems.retry() },
                    )
                }
                pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0 -> {
                    EmptyState(syncState = syncState)
                }
                else -> {
                    PhotosGrid(
                        pagingItems = pagingItems,
                        onPhotoClick = onPhotoClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        
        // Glossy top fade
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(ImageNextBlack, Color.Transparent)
                )
            )
        }
    }
}

@Composable
private fun PhotosGrid(
    pagingItems: LazyPagingItems<TimelineItem>,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            count = pagingItems.itemCount,
            key = { index ->
                when (val item = pagingItems[index]) {
                    is TimelineItem.Header -> "header_${item.date?.toEpochDay() ?: Long.MIN_VALUE}"
                    is TimelineItem.Photo -> item.mediaItem.remotePath
                    null -> "placeholder_$index"
                }
            },
            span = { index ->
                when (pagingItems[index]) {
                    is TimelineItem.Header -> GridItemSpan(GRID_COLUMNS)
                    else -> GridItemSpan(1)
                }
            },
        ) { index ->
            var isVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { isVisible = true }

            AnimatedVisibility(
                visible = isVisible,
                enter = slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = tween(
                        durationMillis = Motion.DURATION_LONG_MS,
                        delayMillis = (index % (GRID_COLUMNS * 4)) * 30, // Subtle stagger
                        easing = Motion.Easing.Emphasized
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = Motion.DURATION_LONG_MS,
                        delayMillis = (index % (GRID_COLUMNS * 4)) * 30
                    )
                )
            ) {
                when (val item = pagingItems[index]) {
                    is TimelineItem.Header -> {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        )
                    }
                    is TimelineItem.Photo -> {
                        ThumbnailCell(
                            mediaItem = item.mediaItem,
                            onClick = { onPhotoClick(item.mediaItem.remotePath) },
                        )
                    }
                    null -> {
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        )
                    }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbnailModel = remember(mediaItem.remotePath, mediaItem.thumbnailPath) {
        resolveGridImageModel(context, mediaItem)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
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
                imageVector = Icons.Default.Photo,
                contentDescription = mediaItem.fileName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.size(32.dp),
            )
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
                text = "DISCOVERING MOMENTS",
                style = MaterialTheme.typography.labelLarge.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Light
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = if (syncState == SyncState.Running || syncState == SyncState.Partial) {
                    "Your gallery is coming to life"
                } else {
                    "Gallery is empty"
                },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text("RETRY CONNECTION", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SyncStatusBar(syncState: SyncState, onRetry: () -> Unit) {
    AnimatedVisibility(
        visible = syncState != SyncState.Idle && syncState != SyncState.Completed,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        when (syncState) {
            SyncState.Running -> {
                Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }
            SyncState.Failed, SyncState.Partial -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (syncState == SyncState.Failed) "Sync failed" else "Partial sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("RETRY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            else -> {}
        }
    }
}

private fun resolveGridImageModel(context: Context, mediaItem: MediaItem): Any? {
    val localThumbnail = mediaItem.thumbnailPath?.let { path ->
        val file = File(path)
        if (file.exists()) file else null
    }
    if (localThumbnail != null) return localThumbnail

    val sessionRepository = SyncDependencies.getSessionRepository(context) ?: return null
    val session = sessionRepository.getSession() ?: return null

    val previewUrl = buildPreviewUrl(
        serverUrl = session.serverUrl,
        remotePath = mediaItem.remotePath,
    )

    val authHeader = buildBasicAuthHeader(
        username = session.loginName,
        password = session.appPassword,
    )

    return ImageRequest.Builder(context)
        .data(previewUrl)
        .httpHeaders(
            NetworkHeaders.Builder()
                .set("Authorization", authHeader)
                .set("OCS-APIRequest", "true")
                .build()
        )
        .build()
}

private fun buildPreviewUrl(serverUrl: String, remotePath: String): String {
    val encodedPath = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    return "$serverUrl/index.php/core/preview?file=$encodedPath&x=512&y=512&a=1"
}

private fun buildBasicAuthHeader(username: String, password: String): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    return "Basic $encoded"
}

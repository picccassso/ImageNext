package com.imagenext.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.SyncState
import java.io.File

private const val GRID_COLUMNS = 3

@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    modifier: Modifier = Modifier,
) {
    val pagingItems = viewModel.timelineItems.collectAsLazyPagingItems()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        // Sync progress indicator
        SyncStatusBar(syncState = syncState, onRetry = { viewModel.retrySync() })

        // Main content
        when {
            // Initial load
            pagingItems.loadState.refresh is LoadState.Loading -> {
                LoadingState()
            }
            // Initial load error
            pagingItems.loadState.refresh is LoadState.Error -> {
                val error = pagingItems.loadState.refresh as LoadState.Error
                ErrorState(
                    message = error.error.localizedMessage ?: "Failed to load photos",
                    onRetry = { pagingItems.retry() },
                )
            }
            // Empty state — no items after load
            pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0 -> {
                EmptyState(syncState = syncState)
            }
            // Content
            else -> {
                PhotosGrid(
                    pagingItems = pagingItems,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SyncStatusBar(
    syncState: SyncState,
    onRetry: () -> Unit,
) {
    when (syncState) {
        SyncState.Running -> {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SyncState.Failed -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text(
                        text = "Sync failed — Retry",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        SyncState.Partial -> {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SyncState.Idle, SyncState.Completed -> {
            // No indicator needed
        }
    }
}

@Composable
private fun PhotosGrid(
    pagingItems: LazyPagingItems<MediaItem>,
    modifier: Modifier = Modifier,
) {
    // Build timeline items from the current paging snapshot
    val snapshot = pagingItems.itemSnapshotList.items
    val timelineItems = snapshot.toTimelineItems()

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        timelineItems.forEach { item ->
            when (item) {
                is TimelineItem.Header -> {
                    item(
                        key = "header_${item.label}",
                        span = { GridItemSpan(GRID_COLUMNS) },
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = 16.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                }
                is TimelineItem.Photo -> {
                    item(key = item.mediaItem.remotePath) {
                        ThumbnailCell(mediaItem = item.mediaItem)
                    }
                }
            }
        }

        // Append loading indicator
        if (pagingItems.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(GRID_COLUMNS) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        // Append error with retry
        if (pagingItems.loadState.append is LoadState.Error) {
            item(span = { GridItemSpan(GRID_COLUMNS) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = { pagingItems.retry() }) {
                        Text("Load more failed — Tap to retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
) {
    val thumbnailModel = mediaItem.thumbnailPath?.let { path ->
        val file = File(path)
        if (file.exists()) file else null
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
            // Placeholder while thumbnail is not yet cached
            Icon(
                imageVector = Icons.Default.Photo,
                contentDescription = mediaItem.fileName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Loading photos…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(syncState: SyncState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = if (syncState == SyncState.Running || syncState == SyncState.Partial) {
                    "Syncing your photos…"
                } else {
                    "No photos yet"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = if (syncState == SyncState.Running || syncState == SyncState.Partial) {
                    "Photos will appear here as they are indexed."
                } else {
                    "Your selected folders don't contain any photos, or sync hasn't started yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

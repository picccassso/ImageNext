package com.imagenext.feature.viewer

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.imagenext.core.model.MediaItem
import com.imagenext.designsystem.ImageNextAccent
import com.imagenext.designsystem.ImageNextBlack
import com.imagenext.designsystem.ImageNextWhite
import com.imagenext.designsystem.Motion
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimum zoom scale. */
private const val MIN_SCALE = 1f

/** Maximum zoom scale. */
private const val MAX_SCALE = 5f

/** Scale applied on double-tap toggle. */
private const val DOUBLE_TAP_SCALE = 3f

/** Number of pages to prefetch on each side of the current page. */
private const val PREFETCH_WINDOW = 1

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val state = uiState) {
            is ViewerUiState.Loading -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is ViewerUiState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = state.message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            is ViewerUiState.Content -> {
                ViewerContent(
                    state = state,
                    onPageChanged = viewModel::onPageChanged,
                    onToggleMetadata = viewModel::toggleMetadata,
                    onBack = onBack,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerContent(
    state: ViewerUiState.Content,
    onPageChanged: (Int) -> Unit,
    onToggleMetadata: () -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex,
        pageCount = { state.items.size },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageChanged(page)
        }
    }

    var showChrome by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        ViewerPager(
            pagerState = pagerState,
            state = state,
            onTap = { showChrome = !showChrome },
        )

        PrefetchLayer(prefetchSources = state.prefetchSources)

        // Top bar overlay with premium translucency
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(animationSpec = tween(Motion.DURATION_MEDIUM_MS)),
            exit = fadeOut(animationSpec = tween(Motion.DURATION_MEDIUM_MS)),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentItem.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = ImageNextWhite,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ImageNextWhite,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleMetadata) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Toggle metadata",
                            tint = if (state.showMetadata) ImageNextAccent else ImageNextWhite,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ImageNextBlack.copy(alpha = 0.6f),
                ),
            )
        }

        // Metadata overlay at the bottom with premium blur effect
        AnimatedVisibility(
            visible = showChrome && state.showMetadata,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MetadataOverlay(mediaItem = state.currentItem)
        }
    }
}

@Composable
private fun ViewerPager(
    pagerState: PagerState,
    state: ViewerUiState.Content,
    onTap: () -> Unit,
) {
    val items = state.items
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = PREFETCH_WINDOW,
        key = { items[it].remotePath },
    ) { page ->
        val mediaItem = items[page]
        val imageSource = when {
            mediaItem.remotePath == state.currentItem.remotePath -> state.currentImageSource
            else -> state.prefetchSources.firstOrNull { it.remotePath == mediaItem.remotePath }
        }

        ZoomableImage(
            mediaItem = mediaItem,
            imageSource = imageSource,
            onTap = onTap,
        )
    }
}

@Composable
private fun ZoomableImage(
    mediaItem: MediaItem,
    imageSource: ViewerImageSource?,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset zoom state when the media item changes
    LaunchedEffect(mediaItem.remotePath) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val context = LocalContext.current
    val imageModel = remember(mediaItem.remotePath, mediaItem.thumbnailPath, imageSource) {
        resolveViewerImageModel(
            context = context,
            mediaItem = mediaItem,
            imageSource = imageSource,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(mediaItem.remotePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (newScale > MIN_SCALE) {
                        // Allow panning only when zoomed in
                        val maxX = (newScale - 1f) * size.width / 2f
                        val maxY = (newScale - 1f) * size.height / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                    scale = newScale
                }
            }
            .pointerInput(mediaItem.remotePath) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        if (scale > MIN_SCALE) {
                            // Reset to default
                            scale = MIN_SCALE
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            // Zoom to double-tap level centered on tap point
                            scale = DOUBLE_TAP_SCALE
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val maxX = (DOUBLE_TAP_SCALE - 1f) * size.width / 2f
                            val maxY = (DOUBLE_TAP_SCALE - 1f) * size.height / 2f
                            offsetX = ((centerX - tapOffset.x) * (DOUBLE_TAP_SCALE - 1f))
                                .coerceIn(-maxX, maxX)
                            offsetY = ((centerY - tapOffset.y) * (DOUBLE_TAP_SCALE - 1f))
                                .coerceIn(-maxY, maxY)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = mediaItem.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        } else {
            // Placeholder when no image is cached
            Icon(
                imageVector = Icons.Default.Photo,
                contentDescription = mediaItem.fileName,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun PrefetchLayer(prefetchSources: List<ViewerImageSource>) {
    if (prefetchSources.isEmpty()) return

    val context = LocalContext.current
    Row(
        modifier = Modifier
            .alpha(0f)
            .size(1.dp),
    ) {
        prefetchSources.forEach { source ->
            val model = remember(source.remotePath, source.fullResUrl, source.authHeader) {
                buildRemoteImageRequest(
                    context = context,
                    url = source.fullResUrl,
                    authHeader = source.authHeader,
                )
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.size(1.dp),
            )
        }
    }
}

private fun resolveViewerImageModel(
    context: Context,
    mediaItem: MediaItem,
    imageSource: ViewerImageSource?,
): Any? {
    val localThumbnail = mediaItem.thumbnailPath?.let { path ->
        val file = File(path)
        if (file.exists()) file else null
    }
    if (localThumbnail != null) return localThumbnail

    if (imageSource == null) return null
    return buildRemoteImageRequest(
        context = context,
        url = imageSource.fullResUrl,
        authHeader = imageSource.authHeader,
    )
}

private fun buildRemoteImageRequest(
    context: Context,
    url: String,
    authHeader: String,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", authHeader).build())
        .build()
}

@Composable
private fun MetadataOverlay(mediaItem: MediaItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ImageNextBlack.copy(alpha = 0.8f))
            .padding(20.dp),
    ) {
        Text(
            text = mediaItem.fileName,
            color = ImageNextWhite,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            MetadataField(label = "Type", value = mediaItem.mimeType)
            MetadataField(label = "Size", value = formatFileSize(mediaItem.size))
        }

        if (mediaItem.lastModified > 0) {
            Text(
                text = "Captured: ${formatDate(mediaItem.lastModified)}",
                color = ImageNextWhite.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            text = mediaItem.folderPath,
            color = ImageNextWhite.copy(alpha = 0.4f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetadataField(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}

private fun formatDate(epochMillis: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMillis))
}

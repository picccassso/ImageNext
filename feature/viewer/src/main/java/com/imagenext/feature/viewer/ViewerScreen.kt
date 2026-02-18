package com.imagenext.feature.viewer

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.imagenext.core.model.MediaItem
import com.imagenext.designsystem.ImageNextAccent
import com.imagenext.designsystem.ImageNextBlack
import com.imagenext.designsystem.ImageNextWhite
import com.imagenext.designsystem.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
private const val VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS = 2500L

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        when (val state = uiState) {
            is ViewerUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                    )
                }
            }

            is ViewerUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
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
    val coroutineScope = rememberCoroutineScope()
    val transitionProgress = remember { Animatable(0f) }
    var isClosingTransition by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageChanged(page)
        }
    }

    var showChrome by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        transitionProgress.animateTo(
            targetValue = 1f,
            animationSpec = Motion.ViewerSpring,
        )
    }

    val transitionRunning by remember {
        derivedStateOf { transitionProgress.value < 1f || isClosingTransition }
    }

    fun onBackRequested() {
        if (isClosingTransition) return
        showChrome = false
        isClosingTransition = true
        coroutineScope.launch {
            transitionProgress.animateTo(
                targetValue = 0f,
                animationSpec = Motion.ViewerSpring,
            )
            onBack()
        }
    }

    BackHandler(onBack = ::onBackRequested)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = transitionProgress.value))
            }
    ) {
        val transitionModifier = remember(transitionProgress) {
            Modifier.graphicsLayer {
                alpha = transitionProgress.value.coerceIn(0f, 1f)
            }
        }

        ViewerPager(
            pagerState = pagerState,
            state = state,
            onTap = {
                if (!transitionRunning) {
                    showChrome = !showChrome
                }
            },
            showChrome = showChrome,
            onChromeVisibilityChange = { visible -> showChrome = visible },
            interactionsEnabled = !transitionRunning,
            modifier = transitionModifier,
        )

        PrefetchLayer(prefetchSources = state.prefetchImageSources)

        val chromeReady by remember {
            derivedStateOf { !isClosingTransition && transitionProgress.value > 0.7f }
        }
        AnimatedVisibility(
            visible = showChrome && chromeReady,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentItem.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = ImageNextWhite,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::onBackRequested) {
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
                    containerColor = ImageNextBlack.copy(alpha = 0.4f),
                ),
            )
        }

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
    showChrome: Boolean,
    onChromeVisibilityChange: (Boolean) -> Unit,
    interactionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = state.items
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = PREFETCH_WINDOW,
        userScrollEnabled = interactionsEnabled,
        key = { items[it].remotePath },
    ) { page ->
        val mediaItem = items[page]
        val mediaSource = when {
            mediaItem.remotePath == state.currentItem.remotePath -> state.currentMediaSource
            else -> state.prefetchImageSources.firstOrNull { it.remotePath == mediaItem.remotePath }
        }

        if (mediaItem.isVideo) {
            VideoPage(
                mediaItem = mediaItem,
                mediaSource = mediaSource,
                isActivePage = page == state.currentIndex,
                showChrome = showChrome,
                onChromeVisibilityChange = onChromeVisibilityChange,
                onTap = onTap,
            )
        } else {
            ZoomableImage(
                mediaItem = mediaItem,
                imageSource = mediaSource,
                onTap = onTap,
                interactionsEnabled = interactionsEnabled,
            )
        }
    }
}

@Composable
private fun VideoPage(
    mediaItem: MediaItem,
    mediaSource: ViewerMediaSource?,
    isActivePage: Boolean,
    showChrome: Boolean,
    onChromeVisibilityChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    if (!isActivePage) {
        VideoPoster(
            mediaItem = mediaItem,
            mediaSource = mediaSource,
            onTap = onTap,
        )
        return
    }

    ActiveVideoPlayer(
        mediaItem = mediaItem,
        mediaSource = mediaSource,
        showChrome = showChrome,
        onChromeVisibilityChange = onChromeVisibilityChange,
        onTap = onTap,
    )
}

@Composable
private fun VideoPoster(
    mediaItem: MediaItem,
    mediaSource: ViewerMediaSource?,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val localThumbnail = remember(mediaItem.remotePath, mediaItem.thumbnailPath) {
        mediaItem.thumbnailPath?.let { path ->
            val file = File(path)
            if (file.exists()) file else null
        }
    }
    val posterRequest = remember(mediaItem.remotePath, mediaItem.thumbnailPath, mediaSource) {
        when {
            localThumbnail != null -> ImageRequest.Builder(context)
                .data(localThumbnail)
                .build()
            mediaSource != null -> buildRemoteImageRequest(
                context = context,
                url = mediaSource.previewUrl,
                authHeader = mediaSource.authHeader,
            )
            else -> null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(mediaItem.remotePath) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (posterRequest != null) {
            AsyncImage(
                model = posterRequest,
                contentDescription = mediaItem.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = mediaItem.fileName,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp),
            )
        }

        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(54.dp),
        )
    }
}

@Composable
private fun ActiveVideoPlayer(
    mediaItem: MediaItem,
    mediaSource: ViewerMediaSource?,
    showChrome: Boolean,
    onChromeVisibilityChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    if (mediaSource == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mediaItem.remotePath) {
                    detectTapGestures(onTap = { onTap() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = mediaItem.fileName,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp),
            )
        }
        return
    }

    val context = LocalContext.current
    val exoPlayer = remember(mediaItem.remotePath) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }
    var isPlaying by remember(mediaItem.remotePath) { mutableStateOf(false) }
    var isMuted by remember(mediaItem.remotePath) { mutableStateOf(false) }
    var durationMs by remember(mediaItem.remotePath) { mutableStateOf(0L) }
    var positionMs by remember(mediaItem.remotePath) { mutableStateOf(0L) }
    var isScrubbing by remember(mediaItem.remotePath) { mutableStateOf(false) }
    var scrubPositionMs by remember(mediaItem.remotePath) { mutableStateOf(0L) }
    var lastInteractionTick by remember(mediaItem.remotePath) {
        mutableStateOf(SystemClock.elapsedRealtime())
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                durationMs = player.duration.coerceAtLeast(0L)
                if (!isScrubbing) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                }
            }
        }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(mediaSource.fullResUrl, mediaSource.authHeader) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf("Authorization" to mediaSource.authHeader)
            )

        val mediaItem = ExoMediaItem.fromUri(mediaSource.fullResUrl)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        exoPlayer.prepare()
        exoPlayer.volume = if (isMuted) 0f else 1f
        exoPlayer.playWhenReady = true
        positionMs = 0L
        scrubPositionMs = 0L
        lastInteractionTick = SystemClock.elapsedRealtime()
        onChromeVisibilityChange(true)
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(showChrome) {
        if (showChrome) {
            lastInteractionTick = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(exoPlayer, isScrubbing) {
        while (true) {
            if (!isScrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(200)
        }
    }

    LaunchedEffect(showChrome, isPlaying, isScrubbing, lastInteractionTick) {
        if (!shouldAutoHideVideoControls(showChrome = showChrome, isPlaying = isPlaying, isScrubbing = isScrubbing)) {
            return@LaunchedEffect
        }
        val interactionSnapshot = lastInteractionTick
        delay(VIDEO_CONTROLS_AUTO_HIDE_DELAY_MS)
        if (
            shouldAutoHideVideoControls(showChrome = showChrome, isPlaying = isPlaying, isScrubbing = isScrubbing) &&
            interactionSnapshot == lastInteractionTick
        ) {
            onChromeVisibilityChange(false)
        }
    }

    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val currentPositionMs = if (isScrubbing) scrubPositionMs else positionMs
    val clampedPositionMs = if (safeDurationMs > 0L) {
        currentPositionMs.coerceIn(0L, safeDurationMs)
    } else {
        currentPositionMs.coerceAtLeast(0L)
    }
    val sliderEnabled = safeDurationMs > 0L
    val sliderValue = if (sliderEnabled) clampedPositionMs.toFloat() else 0f
    val sliderRange = if (sliderEnabled) 0f..safeDurationMs.toFloat() else 0f..1f

    fun registerInteraction() {
        lastInteractionTick = SystemClock.elapsedRealtime()
        onChromeVisibilityChange(true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(mediaItem.remotePath) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showChrome) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        )
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                            registerInteraction()
                        },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    Text(
                        text = "${formatVideoTime(clampedPositionMs)} / ${formatVideoTime(safeDurationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            registerInteraction()
                        },
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isMuted) "Sound off" else "Sound on",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.size(4.dp))

                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        if (!sliderEnabled) return@Slider
                        if (!isScrubbing) {
                            isScrubbing = true
                        }
                        scrubPositionMs = newValue.toLong()
                        registerInteraction()
                    },
                    onValueChangeFinished = {
                        if (!sliderEnabled) return@Slider
                        val seekTargetMs = scrubPositionMs.coerceIn(0L, safeDurationMs)
                        exoPlayer.seekTo(seekTargetMs)
                        positionMs = seekTargetMs
                        isScrubbing = false
                        registerInteraction()
                    },
                    valueRange = sliderRange,
                    enabled = sliderEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.95f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.32f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    mediaItem: MediaItem,
    imageSource: ViewerMediaSource?,
    onTap: () -> Unit,
    interactionsEnabled: Boolean,
) {
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(mediaItem.remotePath) {
        scale.snapTo(1f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    val context = LocalContext.current
    val localThumbnail = remember(mediaItem.remotePath, mediaItem.thumbnailPath) {
        mediaItem.thumbnailPath?.let { path ->
            val file = File(path)
            if (file.exists()) file else null
        }
    }
    val imageRequest = remember(mediaItem.remotePath, mediaItem.thumbnailPath, imageSource) {
        when {
            imageSource != null -> buildRemoteImageRequest(
                context = context,
                url = imageSource.fullResUrl,
                authHeader = imageSource.authHeader,
            )
            localThumbnail != null -> ImageRequest.Builder(context)
                .data(localThumbnail)
                .build()
            else -> null
        }
    }
    val interactionModifier = if (interactionsEnabled) {
        Modifier
            .pointerInput(mediaItem.remotePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    coroutineScope.launch {
                        if (newScale > MIN_SCALE) {
                            val maxX = (newScale - 1f) * size.width / 2f
                            val maxY = (newScale - 1f) * size.height / 2f
                            scale.snapTo(newScale)
                            offsetX.snapTo((offsetX.value + pan.x).coerceIn(-maxX, maxX))
                            offsetY.snapTo((offsetY.value + pan.y).coerceIn(-maxY, maxY))
                        } else {
                            scale.snapTo(newScale)
                            offsetX.snapTo(0f)
                            offsetY.snapTo(0f)
                        }
                    }
                }
            }
            .pointerInput(mediaItem.remotePath) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        coroutineScope.launch {
                            if (scale.value > MIN_SCALE) {
                                launch { scale.animateTo(MIN_SCALE, Motion.ZoomSpring) }
                                launch { offsetX.animateTo(0f, Motion.ZoomSpring) }
                                launch { offsetY.animateTo(0f, Motion.ZoomSpring) }
                            } else {
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val maxX = (DOUBLE_TAP_SCALE - 1f) * size.width / 2f
                                val maxY = (DOUBLE_TAP_SCALE - 1f) * size.height / 2f
                                val targetX = ((centerX - tapOffset.x) * (DOUBLE_TAP_SCALE - 1f))
                                    .coerceIn(-maxX, maxX)
                                val targetY = ((centerY - tapOffset.y) * (DOUBLE_TAP_SCALE - 1f))
                                    .coerceIn(-maxY, maxY)
                                launch { scale.animateTo(DOUBLE_TAP_SCALE, Motion.ZoomSpring) }
                                launch { offsetX.animateTo(targetX, Motion.ZoomSpring) }
                                launch { offsetY.animateTo(targetY, Motion.ZoomSpring) }
                            }
                        }
                    },
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        val graphicsModifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale.value,
                scaleY = scale.value,
                translationX = offsetX.value,
                translationY = offsetY.value,
            )

        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = mediaItem.fileName,
                contentScale = ContentScale.Fit,
                modifier = graphicsModifier,
            )
        } else {
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
private fun PrefetchLayer(prefetchSources: List<ViewerMediaSource>) {
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

private fun buildRemoteImageRequest(
    context: Context,
    url: String,
    authHeader: String,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", authHeader).build())
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(150)
        .build()
}

@Composable
private fun MetadataOverlay(mediaItem: MediaItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ImageNextBlack.copy(alpha = 0.6f))
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

internal fun formatVideoTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val totalMinutes = totalSeconds / 60
    val minutes = totalMinutes % 60
    val hours = totalMinutes / 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun shouldAutoHideVideoControls(
    showChrome: Boolean,
    isPlaying: Boolean,
    isScrubbing: Boolean,
): Boolean {
    return showChrome && isPlaying && !isScrubbing
}

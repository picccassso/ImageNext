package com.imagenext.feature.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val MIN_THUMB_DRAG_WIDTH = 48.dp
private val THUMB_WIDTH = 24.dp
private val THUMB_HEIGHT = 48.dp
private val HIDE_DELAY_MS = 1500L
private val SLOW_SCROLL_X_THRESHOLD_DP = 100.dp 

@Composable
fun PhotosScrollbar(
    gridState: LazyGridState,
    pagingItems: LazyPagingItems<TimelineItem>,
    modifier: Modifier = Modifier
) {
    if (pagingItems.itemCount == 0) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var rawDragY by remember { mutableFloatStateOf(0f) }
    var interactionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isHoverVisible by remember { mutableStateOf(false) }

    // Fast vs slow scroll state
    var isSlowScrolling by remember { mutableStateOf(false) }
    var slowScrollReferenceIndex by remember { mutableIntStateOf(0) }
    var slowScrollReferenceY by remember { mutableFloatStateOf(0f) }

    // Constants for slow scrolling logic
    val slowScrollPxPerMonth = with(density) { 150.dp.toPx() } // PX to drag to jump one month
    val jumpThresholdX = with(density) { SLOW_SCROLL_X_THRESHOLD_DP.toPx() }

    // Auto-hide logic
    LaunchedEffect(interactionTime, isDragging, gridState.isScrollInProgress) {
        if (isDragging || gridState.isScrollInProgress) {
            isHoverVisible = true
        } else {
            delay(HIDE_DELAY_MS)
            isHoverVisible = false
        }
    }

    // Determine current logical scroll proportion based on either drag intent or actual list position
    val scrollProportion by remember(isDragging, rawDragY, containerHeight, gridState.firstVisibleItemIndex, pagingItems.itemCount) {
        derivedStateOf {
            if (isDragging && containerHeight > 0) {
                (rawDragY / containerHeight).coerceIn(0f, 1f)
            } else {
                val index = gridState.firstVisibleItemIndex
                if (pagingItems.itemCount > 0) {
                    (index.toFloat() / pagingItems.itemCount).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    // Determine target index
    val targetIndex = remember(scrollProportion, pagingItems.itemCount, isSlowScrolling, rawDragY) {
        if (pagingItems.itemCount == 0) return@remember 0
        
        val newIndex = if (isSlowScrolling) {
            // For slow scroll, compute delta in "months" based on Y drag distance from reference
            val deltaY = rawDragY - slowScrollReferenceY
            val monthsToJump = (deltaY / slowScrollPxPerMonth).roundToInt()
            
            // Find the closest index representing the target month jump
            findHeaderIndexByMonthDelta(pagingItems, slowScrollReferenceIndex, monthsToJump)
        } else {
            // Fast scroll
            (scrollProportion * pagingItems.itemCount).toInt().coerceIn(0, pagingItems.itemCount - 1)
        }
        
        newIndex
    }

    // Perform actual scroll when tracking index changes during drag
    LaunchedEffect(isDragging, targetIndex) {
        if (isDragging && targetIndex >= 0 && targetIndex < pagingItems.itemCount) {
            coroutineScope.launch {
                // Instantly snap to item
                gridState.scrollToItem(targetIndex)
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isHoverVisible || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "ScrollbarAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(MIN_THUMB_DRAG_WIDTH)
            .onGloballyPositioned { coords ->
                containerHeight = coords.size.height.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        rawDragY = offset.y
                        interactionTime = System.currentTimeMillis()
                        isSlowScrolling = false
                    },
                    onDragEnd = {
                        isDragging = false
                        isSlowScrolling = false
                        interactionTime = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isDragging = false
                        isSlowScrolling = false
                        interactionTime = System.currentTimeMillis()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        rawDragY = (rawDragY + dragAmount.y).coerceIn(0f, containerHeight)
                        interactionTime = System.currentTimeMillis()

                        // Detect X distance from right edge to trigger slow scrubbing
                        val distanceFromEdge = -change.position.x 
                        
                        // Transition between fast and slow scrolling modes
                        if (distanceFromEdge > jumpThresholdX && !isSlowScrolling) {
                            // Enter slow scroll mode
                            isSlowScrolling = true
                            slowScrollReferenceIndex = targetIndex
                            slowScrollReferenceY = rawDragY
                        } else if (distanceFromEdge <= jumpThresholdX && isSlowScrolling) {
                            // Exit slow scroll mode, fall back to proportional scroll instantly
                            isSlowScrolling = false
                        }
                    }
                )
            }
    ) {
        // We calculate thumb vertical translation by removing the thumb height to 
        // prevent it from going out of bounds
        val targetThumbTranslationY = if (containerHeight > 0) {
            val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
            scrollProportion * (containerHeight - thumbPx)
        } else {
            0f
        }

        val thumbTranslationY by animateFloatAsState(
            targetValue = targetThumbTranslationY,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
            ),
            label = "ThumbTranslationY"
        )

        // Animated Bubble & Thumb Container
        if (alpha > 0f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        translationY = thumbTranslationY
                        this.alpha = alpha
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scrollbar Thumb
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                        .background(
                            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

/**
 * Helper to jump backwards/forwards across headers by `monthDelta`.
 * -1 month = find the header prior to the reference's month.
 * +1 month = find the header after the reference's month.
 */
private fun findHeaderIndexByMonthDelta(
    pagingItems: LazyPagingItems<TimelineItem>,
    referenceIndex: Int,
    monthDelta: Int
): Int {
    if (monthDelta == 0) return referenceIndex
    if (pagingItems.itemCount == 0) return 0
    
    // Find our current header
    var currentHeaderIndex = referenceIndex
    var currentHeader: TimelineItem.Header? = null
    while (currentHeaderIndex >= 0) {
        val item = pagingItems.peek(currentHeaderIndex)
        if (item is TimelineItem.Header) {
            currentHeader = item
            break
        }
        currentHeaderIndex--
    }
    
    if (currentHeader?.date == null) return referenceIndex
    
    // Move monthDelta times by jumping across headers
    var jumpCount = 0
    val targetJumps = abs(monthDelta)
    val step = if (monthDelta > 0) 1 else -1
    
    var searchIdx = currentHeaderIndex + step
    var lastValidHeaderIdx = currentHeaderIndex
    var lastSeenMonthAndYear = Pair(currentHeader.date.monthValue, currentHeader.date.year)
    
    while (searchIdx in 0 until pagingItems.itemCount && jumpCount < targetJumps) {
        val item = pagingItems.peek(searchIdx)
        if (item is TimelineItem.Header && item.date != null) {
            val hMonthYear = Pair(item.date.monthValue, item.date.year)
            if (hMonthYear != lastSeenMonthAndYear) {
                lastSeenMonthAndYear = hMonthYear
                jumpCount++
                lastValidHeaderIdx = searchIdx
            }
        }
        searchIdx += step
    }
    
    return lastValidHeaderIdx.coerceIn(0, pagingItems.itemCount - 1)
}

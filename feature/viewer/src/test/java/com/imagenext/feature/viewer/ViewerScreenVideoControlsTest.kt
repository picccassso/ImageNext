package com.imagenext.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenVideoControlsTest {

    private val dismissThresholdPx = 120f

    @Test
    fun `formatVideoTime handles minute boundaries`() {
        assertEquals("0:00", formatVideoTime(0))
        assertEquals("0:59", formatVideoTime(59_000))
        assertEquals("1:00", formatVideoTime(60_000))
    }

    @Test
    fun `formatVideoTime handles hour boundaries`() {
        assertEquals("59:59", formatVideoTime(3_599_000))
        assertEquals("1:00:00", formatVideoTime(3_600_000))
        assertEquals("2:03:04", formatVideoTime(7_384_000))
    }

    @Test
    fun `shouldAutoHideVideoControls is true only while playing with visible controls and no scrub`() {
        assertTrue(
            shouldAutoHideVideoControls(
                showChrome = true,
                isPlaying = true,
                isScrubbing = false,
            )
        )
        assertFalse(
            shouldAutoHideVideoControls(
                showChrome = false,
                isPlaying = true,
                isScrubbing = false,
            )
        )
        assertFalse(
            shouldAutoHideVideoControls(
                showChrome = true,
                isPlaying = false,
                isScrubbing = false,
            )
        )
        assertFalse(
            shouldAutoHideVideoControls(
                showChrome = true,
                isPlaying = true,
                isScrubbing = true,
            )
        )
    }

    @Test
    fun `shouldDismissMetadataOnSwipe returns true for downward vertical drag above threshold`() {
        assertTrue(
            shouldDismissMetadataOnSwipe(
                totalDragX = 16f,
                totalDragY = 180f,
                dismissThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldDismissMetadataOnSwipe returns false for upward drag`() {
        assertFalse(
            shouldDismissMetadataOnSwipe(
                totalDragX = 10f,
                totalDragY = -180f,
                dismissThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldDismissMetadataOnSwipe returns false when horizontal drag dominates`() {
        assertFalse(
            shouldDismissMetadataOnSwipe(
                totalDragX = 220f,
                totalDragY = 160f,
                dismissThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldDismissMetadataOnSwipe returns false for small downward drag below threshold`() {
        assertFalse(
            shouldDismissMetadataOnSwipe(
                totalDragX = 4f,
                totalDragY = 90f,
                dismissThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldShowMetadataOnSwipe returns true for upward vertical drag above threshold`() {
        assertTrue(
            shouldShowMetadataOnSwipe(
                totalDragX = 12f,
                totalDragY = -180f,
                showThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldShowMetadataOnSwipe returns false for downward drag`() {
        assertFalse(
            shouldShowMetadataOnSwipe(
                totalDragX = 12f,
                totalDragY = 180f,
                showThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldShowMetadataOnSwipe returns false when horizontal drag dominates`() {
        assertFalse(
            shouldShowMetadataOnSwipe(
                totalDragX = 240f,
                totalDragY = -170f,
                showThresholdPx = dismissThresholdPx,
            )
        )
    }

    @Test
    fun `shouldShowMetadataOnSwipe returns false for small upward drag below threshold`() {
        assertFalse(
            shouldShowMetadataOnSwipe(
                totalDragX = 6f,
                totalDragY = -95f,
                showThresholdPx = dismissThresholdPx,
            )
        )
    }
}

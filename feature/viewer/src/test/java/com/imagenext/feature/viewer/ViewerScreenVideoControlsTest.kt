package com.imagenext.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenVideoControlsTest {

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
}

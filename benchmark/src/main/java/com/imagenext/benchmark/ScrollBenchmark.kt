package com.imagenext.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * Scroll benchmark scenarios for ImageNext Photos timeline.
 *
 * Measures frame timing and jank during timeline scrolling.
 * Run on a physical device for accurate results.
 *
 * Run with: ./gradlew :benchmark:connectedBenchmarkAndroidTest
 */
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Photos timeline scroll performance.
     * Measures P50/P90/P95/P99 frame durations during rapid scrolling.
     * Target: P95 frame time < 16ms (60fps) per MASTERPLAN §10.3.
     */
    @Test
    fun scrollPhotosTimeline() = benchmarkRule.measureRepeated(
        packageName = "com.imagenext",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT,
    ) {
        pressHome()
        startActivityAndWait()

        // Navigate to Photos tab and scroll the timeline
        // The Photos tab is the default tab, so we can start scrolling immediately.
        val timeline = device.findObject(
            androidx.test.uiautomator.By.scrollable(true)
        )
        timeline?.let {
            // Perform a series of fling gestures to simulate rapid scrolling
            repeat(5) {
                timeline.fling(androidx.test.uiautomator.Direction.DOWN)
                device.waitForIdle()
            }
            // Scroll back up
            repeat(5) {
                timeline.fling(androidx.test.uiautomator.Direction.UP)
                device.waitForIdle()
            }
        }
    }
}

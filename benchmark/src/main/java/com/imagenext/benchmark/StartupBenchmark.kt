package com.imagenext.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * Startup benchmark scenarios for ImageNext.
 *
 * Measures time-to-initial-display for cold, warm, and hot start modes.
 * Run on a physical device for accurate results.
 *
 * Run with: ./gradlew :benchmark:connectedBenchmarkAndroidTest
 */
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * Cold start: process is killed before each iteration.
     * Target: < 2 seconds (MASTERPLAN §10.1).
     */
    @Test
    fun startupCold() = benchmarkRule.measureRepeated(
        packageName = "com.imagenext",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * Warm start: activity is destroyed but process remains alive.
     */
    @Test
    fun startupWarm() = benchmarkRule.measureRepeated(
        packageName = "com.imagenext",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * Hot start: activity is brought back to foreground.
     */
    @Test
    fun startupHot() = benchmarkRule.measureRepeated(
        packageName = "com.imagenext",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.HOT,
        compilationMode = CompilationMode.DEFAULT,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

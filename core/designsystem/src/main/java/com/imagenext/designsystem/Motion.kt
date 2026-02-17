package com.imagenext.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Motion constants for consistent animation feel.
 */
object Motion {
    /** Ultra-short transitions like ripples and subtle fades. */
    const val DURATION_SHORT_MS = 150

    /** Standard transitions like component entry/exit. */
    const val DURATION_MEDIUM_MS = 250

    /** Emphasized transitions like screen changes. */
    const val DURATION_LONG_MS = 400

    /** Complex orchestrated animations. */
    const val DURATION_EXTRA_LONG_MS = 600

    /** Spring for viewer open/close — critically damped, smooth settle with no overshoot. */
    val ViewerSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 300f,
    )

    /** Spring for zoom animations — snappy with no overshoot. */
    val ZoomSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * Easing curves based on Material 3 motion system.
     */
    object Easing {
        /** Easing for elements entering the screen. */
        val Emphasized: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

        /** Easing for elements leaving the screen. */
        val Decelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

        /** Easing for general movements within the screen. */
        val Standard: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.8f, 1.0f)

        /** Easing for elements leaving the screen — starts fast for a snappy dismiss. */
        val Accelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    }
}

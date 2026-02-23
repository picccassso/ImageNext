package com.imagenext.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Motion constants for consistent animation feel.
 * Follows Material 3 motion guidelines with customizations for ImageNext.
 */
object Motion {
    /** Ultra-short transitions like ripples and subtle fades. */
    const val DURATION_SHORT_MS = 150

    /** Standard transitions like component entry/exit. */
    const val DURATION_MEDIUM_MS = 300

    /** Emphasized transitions like screen changes. */
    const val DURATION_LONG_MS = 400

    /** Complex orchestrated animations. */
    const val DURATION_EXTRA_LONG_MS = 600

    /** Duration for shared element transitions. */
    const val DURATION_SHARED_ELEMENT_MS = 350

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

    /** Spring for screen transitions — smooth with slight momentum. */
    val ScreenSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 380f,
    )

    /** Spring for bottom navigation — quick and responsive. */
    val BottomNavSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * Tween specs for common transitions.
     */
    object Transitions {
        /** Standard fade in — smooth entry with emphasized easing. */
        val FadeIn: TweenSpec<Float> = tween(
            durationMillis = DURATION_MEDIUM_MS,
            easing = Easing.Emphasized,
        )

        /** Standard fade out — quick exit with decelerate easing. */
        val FadeOut: TweenSpec<Float> = tween(
            durationMillis = DURATION_SHORT_MS,
            easing = Easing.Standard,
        )

        /** Slide in from right — for forward navigation. */
        val SlideInRightOffset: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_MEDIUM_MS,
            easing = Easing.Emphasized,
        )

        /** Slide out to left — for forward navigation exit. */
        val SlideOutLeftOffset: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_SHORT_MS,
            easing = Easing.Accelerate,
        )

        /** Slide in from left — for back navigation. */
        val SlideInLeftOffset: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_MEDIUM_MS,
            easing = Easing.Emphasized,
        )

        /** Slide out to right — for back navigation exit. */
        val SlideOutRightOffset: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_SHORT_MS,
            easing = Easing.Accelerate,
        )

        /** Bottom nav slide in — for tab switches. */
        val BottomNavSlideIn: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_MEDIUM_MS,
            easing = Easing.Emphasized,
        )

        /** Bottom nav slide out — for tab switches. */
        val BottomNavSlideOut: TweenSpec<IntOffset> = tween(
            durationMillis = DURATION_SHORT_MS,
            easing = Easing.Accelerate,
        )

        /** Scale fade in — for modal/dialog presentations. */
        val ScaleFadeIn: TweenSpec<Float> = tween(
            durationMillis = DURATION_MEDIUM_MS,
            easing = Easing.Emphasized,
        )

        /** Scale fade out — for modal/dialog dismissals. */
        val ScaleFadeOut: TweenSpec<Float> = tween(
            durationMillis = DURATION_SHORT_MS,
            easing = Easing.Accelerate,
        )

        /** Shared element fade — for image viewer transitions. */
        val SharedElement: TweenSpec<Float> = tween(
            durationMillis = DURATION_SHARED_ELEMENT_MS,
            easing = Easing.Emphasized,
        )

        /** Bottom sheet slide — smooth with emphasized decelerate. */
        val BottomSheet: TweenSpec<Float> = tween(
            durationMillis = DURATION_LONG_MS,
            easing = Easing.EmphasizedDecelerate,
        )
    }

    /**
     * Easing curves based on Material 3 motion system.
     */
    object Easing {
        /** Easing for elements entering the screen — starts slow, accelerates. */
        val Emphasized: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

        /** Easing for elements leaving the screen — starts fast, decelerates. */
        val Decelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

        /** Easing for general movements within the screen. */
        val Standard: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.8f, 1.0f)

        /** Easing for elements leaving the screen — starts fast for a snappy dismiss. */
        val Accelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

        /** Emphasized decelerate — for bottom sheets and modal presentations. */
        val EmphasizedDecelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

        /** Emphasized accelerate — for quick exits. */
        val EmphasizedAccelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    }

    /**
     * Navigation slide distances for screen transitions.
     */
    object SlideDistance {
        /** Standard slide distance for full-screen transitions (30% of screen width). */
        const val FULL = 0.3f

        /** Short slide distance for bottom navigation (8% of screen width). */
        const val SHORT = 0.08f

        /** Modal slide distance for dialogs (5% of screen width). */
        const val MODAL = 0.05f
    }
}

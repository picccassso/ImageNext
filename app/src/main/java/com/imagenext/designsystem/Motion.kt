package com.imagenext.designsystem

/**
 * Motion duration constants for consistent animation timing.
 * Use these values instead of ad hoc durations.
 */
object Motion {
    /** Ultra-short transitions like ripples and fades. */
    const val DURATION_SHORT_MS = 150

    /** Standard transitions like screen element changes. */
    const val DURATION_MEDIUM_MS = 300

    /** Emphasized transitions like full-screen changes. */
    const val DURATION_LONG_MS = 500

    /** Complex orchestrated animations. */
    const val DURATION_EXTRA_LONG_MS = 700
}

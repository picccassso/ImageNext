package com.imagenext.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * App lock policy abstraction.
 *
 * Manages an optional app lock feature that requires user re-authentication
 * when returning to the app after a configured timeout. Uses non-encrypted
 * SharedPreferences since this stores only policy flags, not credentials.
 *
 * Lifecycle behavior:
 * - On app pause: records the timestamp.
 * - On app resume: compares elapsed time against the lock timeout threshold.
 * - If exceeded, [shouldShowLockOnResume] returns true until [onUnlocked] is called.
 */
class AppLockManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_FILE_NAME,
        Context.MODE_PRIVATE,
    )

    /** Timestamp (elapsed realtime millis) when app was last paused. */
    @Volatile
    private var lastPauseElapsedMs: Long = 0L

    /** Whether the lock screen should be shown. */
    @Volatile
    private var lockPending: Boolean = false

    /**
     * Returns whether app lock is currently enabled.
     */
    fun isLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOCK_ENABLED, false)
    }

    /**
     * Enables or disables app lock.
     */
    fun setLockEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_LOCK_ENABLED, enabled)
            .apply()
        if (!enabled) {
            lockPending = false
        }
    }

    /**
     * Returns the lock timeout duration in milliseconds.
     */
    fun getLockTimeoutMs(): Long {
        return prefs.getLong(KEY_LOCK_TIMEOUT_MS, DEFAULT_LOCK_TIMEOUT_MS)
    }

    /**
     * Sets the lock timeout duration in milliseconds.
     */
    fun setLockTimeoutMs(timeoutMs: Long) {
        prefs.edit()
            .putLong(KEY_LOCK_TIMEOUT_MS, timeoutMs)
            .apply()
    }

    /**
     * Called when the app moves to the background.
     * Records the pause timestamp for timeout calculation.
     */
    fun onAppPaused() {
        if (isLockEnabled()) {
            lastPauseElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Called when the app returns to the foreground.
     * Determines if the lock screen should be shown based on elapsed time.
     */
    fun onAppResumed() {
        if (!isLockEnabled() || lastPauseElapsedMs == 0L) {
            lockPending = false
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - lastPauseElapsedMs
        lockPending = elapsed >= getLockTimeoutMs()
    }

    /**
     * Returns true if the lock screen should be displayed on resume.
     */
    fun shouldShowLockOnResume(): Boolean {
        return lockPending
    }

    /**
     * Called when the user has successfully authenticated through the lock screen.
     */
    fun onUnlocked() {
        lockPending = false
    }

    /**
     * Resets all app lock state. Used during secure logout.
     */
    fun reset() {
        prefs.edit().clear().apply()
        lockPending = false
        lastPauseElapsedMs = 0L
    }

    private companion object {
        const val PREFS_FILE_NAME = "imagenext_app_lock"
        const val KEY_LOCK_ENABLED = "lock_enabled"
        const val KEY_LOCK_TIMEOUT_MS = "lock_timeout_ms"
        const val DEFAULT_LOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
}

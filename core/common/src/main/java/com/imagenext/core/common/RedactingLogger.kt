package com.imagenext.core.common

import android.util.Log

/**
 * Standardized redaction-safe logging utility.
 *
 * All diagnostic output should route through this logger to enforce
 * the security policy: tokens, passwords, and server secrets must never
 * appear in logs.
 *
 * Redaction is applied via regex patterns before any log call is dispatched.
 * Diagnostics data remains local by default — no external send path exists.
 */
object RedactingLogger {

    private val REDACTION_PATTERNS = listOf(
        // App passwords / tokens (alphanumeric strings 20+ chars after known keys)
        Regex("""(password|appPassword|app_password|token|appToken|app_token|secret)\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE)
            to "$1=***",
        // Authorization headers
        Regex("""(Authorization\s*[:=]\s*)(Basic\s+|Bearer\s+)\S+""", RegexOption.IGNORE_CASE)
            to "$1$2***",
        // URL credentials (https://user:pass@host)
        Regex("""(https?://[^:]+:)\S+(@)""", RegexOption.IGNORE_CASE)
            to "$1***$2",
    )

    /** Log at DEBUG level with redaction applied. */
    fun d(tag: String, message: String) {
        Log.d(tag, redact(message))
    }

    /** Log at INFO level with redaction applied. */
    fun i(tag: String, message: String) {
        Log.i(tag, redact(message))
    }

    /** Log at WARN level with redaction applied. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, redact(message), throwable)
        } else {
            Log.w(tag, redact(message))
        }
    }

    /** Log at ERROR level with redaction applied. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, redact(message), throwable)
        } else {
            Log.e(tag, redact(message))
        }
    }

    /**
     * Applies all redaction patterns to the given message.
     *
     * Visible for testing — production code should use the log methods.
     */
    fun redact(message: String): String {
        var result = message
        for ((pattern, replacement) in REDACTION_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}

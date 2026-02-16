package com.imagenext.feature.onboarding

/**
 * Sealed interface defining all onboarding UI states.
 *
 * The state machine is deterministic — every state has clearly defined transitions
 * for success, failure, retry, and cancel actions.
 *
 * State transitions:
 * ```
 * Welcome → ServerSetup → Connecting → Login → Authenticating → Complete
 *                  ↑            ↓          ↑          ↓
 *                  └── Error ←─┘          └── Error ←┘
 * ```
 */
sealed interface OnboardingState {

    /** Initial entry screen. User sees welcome message and "Get Started" button. */
    data object Welcome : OnboardingState

    /** User enters server URL. */
    data object ServerSetup : OnboardingState

    /** Checking server connectivity after URL submission. */
    data class Connecting(val serverUrl: String) : OnboardingState

    /** Server validated. User chooses Login Flow v2 or manual app-password. */
    data class Login(val serverUrl: String) : OnboardingState

    /** Authentication in progress (Login Flow v2 polling or manual credential check). */
    data class Authenticating(val serverUrl: String) : OnboardingState

    /** Authentication succeeded. Session has been persisted. */
    data object Complete : OnboardingState

    /** Error state with user-readable message and recovery action. */
    data class Error(
        val message: String,
        /** The state to return to when user taps "Try Again". */
        val retryTarget: OnboardingState,
    ) : OnboardingState
}

/**
 * Login mode selection for the auth step.
 */
enum class LoginMode {
    /** Nextcloud Browser Login Flow v2 (primary). */
    BROWSER_LOGIN_FLOW,
    /** Manual username + app-password entry (fallback). */
    MANUAL_APP_PASSWORD,
}

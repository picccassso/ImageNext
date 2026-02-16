package com.imagenext.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.imagenext.core.model.AuthSession
import com.imagenext.core.network.auth.LoginFlowClient
import com.imagenext.core.network.auth.NextcloudAuthApi
import com.imagenext.core.network.auth.ServerUrlValidator
import com.imagenext.core.security.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Onboarding state orchestrator.
 *
 * Manages the complete onboarding flow from Welcome → Complete,
 * coordinating server validation, Login Flow v2, manual fallback,
 * and secure session persistence.
 *
 * Poll state (endpoint, token, server URL) is persisted to disk so that
 * polling can resume after activity recreation or process death.
 */
class OnboardingViewModel(
    private val authApi: NextcloudAuthApi,
    private val loginFlowClient: LoginFlowClient,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Welcome)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** The Login Flow v2 login URL to open in the browser, if initiated. */
    private val _loginFlowUrl = MutableStateFlow<String?>(null)
    val loginFlowUrl: StateFlow<String?> = _loginFlowUrl.asStateFlow()

    /** Tracks the validated server URL for use across auth steps. */
    private var validatedServerUrl: String = ""

    /** Login Flow v2 poll data. */
    private var pollEndpoint: String? = null
    private var pollToken: String? = null
    private var pollJob: Job? = null

    init {
        resumePendingPollIfNeeded()
    }

    /**
     * On init, checks for persisted poll state from a previous instance
     * (e.g., after process death while the user was in the browser).
     * If found, restores in-memory state and resumes polling.
     */
    private fun resumePendingPollIfNeeded() {
        val pending = sessionRepository.getPendingPollState() ?: return

        val (endpoint, token, serverUrl) = pending
        pollEndpoint = endpoint
        pollToken = token
        validatedServerUrl = serverUrl
        _state.value = OnboardingState.Authenticating(serverUrl)
        startPolling(serverUrl)
    }

    // -- State transitions --

    /** User taps "Get Started" on the welcome screen. */
    fun onGetStarted() {
        _state.value = OnboardingState.ServerSetup
    }

    /**
     * User submits a server URL. Validates and checks reachability.
     */
    fun onServerUrlSubmitted(rawUrl: String) {
        when (val validation = ServerUrlValidator.validate(rawUrl)) {
            is ServerUrlValidator.ValidationResult.Invalid -> {
                _state.value = OnboardingState.Error(
                    message = validation.reason,
                    retryTarget = OnboardingState.ServerSetup,
                )
            }
            is ServerUrlValidator.ValidationResult.Valid -> {
                val normalizedUrl = validation.normalizedUrl
                _state.value = OnboardingState.Connecting(normalizedUrl)

                viewModelScope.launch(Dispatchers.IO) {
                    when (val result = authApi.checkServerReachability(normalizedUrl)) {
                        is NextcloudAuthApi.AuthResult.Success -> {
                            validatedServerUrl = normalizedUrl
                            _state.value = OnboardingState.Login(normalizedUrl)
                        }
                        is NextcloudAuthApi.AuthResult.Error -> {
                            _state.value = OnboardingState.Error(
                                message = result.message,
                                retryTarget = OnboardingState.ServerSetup,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * User chooses Login Flow v2 (browser login).
     * Initiates the flow, persists poll state, and starts polling.
     */
    fun onStartBrowserLogin() {
        val serverUrl = validatedServerUrl
        _state.value = OnboardingState.Authenticating(serverUrl)

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = loginFlowClient.initiate(serverUrl)) {
                is LoginFlowClient.FlowResult.Success -> {
                    val init = result.data
                    pollEndpoint = init.pollEndpoint
                    pollToken = init.pollToken
                    _loginFlowUrl.value = init.loginUrl

                    // Persist poll state so it survives process death
                    sessionRepository.savePendingPollState(
                        pollEndpoint = init.pollEndpoint,
                        pollToken = init.pollToken,
                        serverUrl = serverUrl,
                    )

                    startPolling(serverUrl)
                }
                is LoginFlowClient.FlowResult.Error -> {
                    _state.value = OnboardingState.Error(
                        message = result.message,
                        retryTarget = OnboardingState.Login(serverUrl),
                    )
                }
                is LoginFlowClient.FlowResult.TransientError -> {
                    _state.value = OnboardingState.Error(
                        message = result.message,
                        retryTarget = OnboardingState.Login(serverUrl),
                    )
                }
                is LoginFlowClient.FlowResult.Pending -> {
                    // Not expected from initiate(), but handle gracefully
                    _state.value = OnboardingState.Error(
                        message = "Unexpected response from server. Please try again.",
                        retryTarget = OnboardingState.Login(serverUrl),
                    )
                }
            }
        }
    }

    /**
     * Polls the Login Flow v2 endpoint until login completes or times out.
     * Max poll attempts prevent infinite retry loops.
     */
    private fun startPolling(serverUrl: String) {
        val endpoint = pollEndpoint ?: return
        val token = pollToken ?: return

        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var attempts = 0
            var consecutiveTransientErrors = 0
            var currentDelayMs = POLL_INTERVAL_MS
            val maxAttempts = MAX_POLL_ATTEMPTS

            while (isActive && attempts < maxAttempts) {
                delay(currentDelayMs)
                attempts++

                when (val result = loginFlowClient.poll(endpoint, token, serverUrl)) {
                    is LoginFlowClient.FlowResult.Success -> {
                        onAuthenticationSuccess(result.data)
                        return@launch
                    }
                    is LoginFlowClient.FlowResult.Pending -> {
                        // User hasn't completed browser login yet — continue polling
                        consecutiveTransientErrors = 0
                        currentDelayMs = POLL_INTERVAL_MS
                    }
                    is LoginFlowClient.FlowResult.TransientError -> {
                        // Keep polling through short-lived DNS/network issues instead of
                        // failing the flow on the first transient exception.
                        consecutiveTransientErrors++
                        currentDelayMs = calculateBackoffDelayMs(consecutiveTransientErrors)
                    }
                    is LoginFlowClient.FlowResult.Error -> {
                        sessionRepository.clearPendingPollState()
                        _state.value = OnboardingState.Error(
                            message = result.message,
                            retryTarget = OnboardingState.Login(serverUrl),
                        )
                        return@launch
                    }
                }
            }

            // Exceeded max poll attempts
            if (isActive) {
                sessionRepository.clearPendingPollState()
                _state.value = OnboardingState.Error(
                    message = "Login timed out. Please try again.",
                    retryTarget = OnboardingState.Login(serverUrl),
                )
            }
        }
    }

    /**
     * User submits manual credentials (username + app-password).
     */
    fun onManualLogin(username: String, appPassword: String) {
        val serverUrl = validatedServerUrl

        if (username.isBlank() || appPassword.isBlank()) {
            _state.value = OnboardingState.Error(
                message = "Username and app password are required.",
                retryTarget = OnboardingState.Login(serverUrl),
            )
            return
        }

        _state.value = OnboardingState.Authenticating(serverUrl)

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = authApi.validateCredentials(serverUrl, username, appPassword)) {
                is NextcloudAuthApi.AuthResult.Success -> {
                    onAuthenticationSuccess(result.data)
                }
                is NextcloudAuthApi.AuthResult.Error -> {
                    _state.value = OnboardingState.Error(
                        message = result.message,
                        retryTarget = OnboardingState.Login(serverUrl),
                    )
                }
            }
        }
    }

    /**
     * Handles successful authentication from either login path.
     * Persists the session, clears pending poll state, and transitions to Complete.
     */
    private fun onAuthenticationSuccess(session: AuthSession) {
        sessionRepository.saveSession(session)
        sessionRepository.clearPendingPollState()
        _loginFlowUrl.value = null
        _state.value = OnboardingState.Complete
    }

    /**
     * User taps "Try Again" from an error state.
     * Returns to the [OnboardingState.Error.retryTarget].
     */
    fun onRetry() {
        val current = _state.value
        if (current is OnboardingState.Error) {
            _state.value = current.retryTarget
        }
    }

    /** Navigate back to previous step. */
    fun onBack() {
        pollJob?.cancel()
        sessionRepository.clearPendingPollState()
        _loginFlowUrl.value = null

        when (val current = _state.value) {
            is OnboardingState.ServerSetup -> _state.value = OnboardingState.Welcome
            is OnboardingState.Login -> _state.value = OnboardingState.ServerSetup
            is OnboardingState.Authenticating -> _state.value = OnboardingState.Login(current.serverUrl)
            is OnboardingState.Error -> _state.value = current.retryTarget
            is OnboardingState.Connecting -> _state.value = OnboardingState.ServerSetup
            else -> { /* No back action from Welcome or Complete */ }
        }
    }

    /** Consumed when browser intent has been launched. */
    fun onLoginFlowUrlConsumed() {
        _loginFlowUrl.value = null
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    private companion object {
        /** Poll interval for Login Flow v2 in milliseconds. */
        const val POLL_INTERVAL_MS = 2000L
        /** Maximum number of poll attempts before timeout (2s × 150 = ~5 minutes). */
        const val MAX_POLL_ATTEMPTS = 150
        /** Maximum poll backoff delay for transient network failures. */
        const val MAX_POLL_BACKOFF_MS = 10_000L
        /** Maximum exponential backoff exponent (2^3 = 8x base interval). */
        const val MAX_BACKOFF_EXPONENT = 3
    }

    /**
     * Returns the next poll delay when transient errors occur consecutively.
     * Uses exponential backoff with a cap to avoid tight retry loops.
     */
    private fun calculateBackoffDelayMs(consecutiveTransientErrors: Int): Long {
        if (consecutiveTransientErrors <= 0) return POLL_INTERVAL_MS

        val exponent = min(consecutiveTransientErrors - 1, MAX_BACKOFF_EXPONENT)
        val multiplier = 1L shl exponent
        return min(POLL_INTERVAL_MS * multiplier, MAX_POLL_BACKOFF_MS)
    }
}

/**
 * Factory for [OnboardingViewModel] to support construction via [ViewModelProvider].
 *
 * This ensures the ViewModel survives activity recreation (configuration changes,
 * background activity destruction) — critical for Login Flow v2 where the user
 * is sent to an external browser and the activity may be destroyed.
 */
class OnboardingViewModelFactory(
    private val authApi: NextcloudAuthApi,
    private val loginFlowClient: LoginFlowClient,
    private val sessionRepository: SessionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return OnboardingViewModel(authApi, loginFlowClient, sessionRepository) as T
    }
}

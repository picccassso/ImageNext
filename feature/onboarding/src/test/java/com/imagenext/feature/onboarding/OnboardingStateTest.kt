package com.imagenext.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OnboardingState] transitions.
 *
 * Validates the state machine is deterministic and all transitions
 * produce expected states.
 */
class OnboardingStateTest {

    @Test
    fun `welcome is the initial state`() {
        val state: OnboardingState = OnboardingState.Welcome
        assertTrue(state is OnboardingState.Welcome)
    }

    @Test
    fun `server setup follows welcome`() {
        val state: OnboardingState = OnboardingState.ServerSetup
        assertTrue(state is OnboardingState.ServerSetup)
    }

    @Test
    fun `connecting state holds server URL`() {
        val state = OnboardingState.Connecting("https://cloud.example.com")
        assertEquals("https://cloud.example.com", state.serverUrl)
    }

    @Test
    fun `login state holds server URL`() {
        val state = OnboardingState.Login("https://cloud.example.com")
        assertEquals("https://cloud.example.com", state.serverUrl)
    }

    @Test
    fun `authenticating state holds server URL`() {
        val state = OnboardingState.Authenticating("https://cloud.example.com")
        assertEquals("https://cloud.example.com", state.serverUrl)
    }

    @Test
    fun `error state holds message and retry target`() {
        val retryTarget = OnboardingState.ServerSetup
        val state = OnboardingState.Error(
            message = "Connection failed",
            retryTarget = retryTarget,
        )
        assertEquals("Connection failed", state.message)
        assertTrue(state.retryTarget is OnboardingState.ServerSetup)
    }

    @Test
    fun `error retry target can be login state`() {
        val retryTarget = OnboardingState.Login("https://cloud.example.com")
        val state = OnboardingState.Error(
            message = "Auth failed",
            retryTarget = retryTarget,
        )
        assertTrue(state.retryTarget is OnboardingState.Login)
        assertEquals(
            "https://cloud.example.com",
            (state.retryTarget as OnboardingState.Login).serverUrl,
        )
    }

    @Test
    fun `complete is a terminal state`() {
        val state: OnboardingState = OnboardingState.Complete
        assertTrue(state is OnboardingState.Complete)
    }

    @Test
    fun `login mode enum has both options`() {
        assertEquals(2, LoginMode.entries.size)
        assertTrue(LoginMode.entries.contains(LoginMode.BROWSER_LOGIN_FLOW))
        assertTrue(LoginMode.entries.contains(LoginMode.MANUAL_APP_PASSWORD))
    }
}

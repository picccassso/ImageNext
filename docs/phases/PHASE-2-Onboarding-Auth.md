# Phase 2: Onboarding and Authentication

## Scope
Deliver an easy and secure onboarding flow with browser-based Nextcloud Login Flow v2 as primary authentication and manual app-password fallback.

## Objectives
1. Keep login friction minimal.
2. Support secure session persistence and restoration.
3. Enforce safe error handling for invalid credentials and server issues.

## Implementation Criteria
1. User can complete welcome, server setup, and authentication without dead-end states.
2. Browser Login Flow v2 works for standard Nextcloud configurations.
3. Manual fallback using app-password works when Login Flow v2 is unavailable.
4. Session persists securely and restores app start route.

## Task Breakdown
### Task 2.1: Define onboarding state machine and route transitions
1. Define deterministic states from welcome to post-login routing.
2. Specify transitions for success, failure, retry, and cancel actions.
3. Document handling for interrupted onboarding sessions.

Done criteria:
1. Onboarding state transitions are decision-complete.
2. No ambiguous route behavior remains.

### Task 2.2: Implement server URL normalization and connectivity pre-check
1. Normalize user server input to enforce HTTPS conventions.
2. Validate server reachability before starting auth.
3. Provide actionable errors for DNS, timeout, TLS, and invalid URL format.

Done criteria:
1. Invalid server input is rejected with clear guidance.
2. Connectivity failures surface user-readable recovery actions.

### Task 2.3: Integrate browser Login Flow v2
1. Implement flow initiation, browser handoff, and callback handling.
2. Validate callback payload and session material integrity.
3. Ensure flow is recoverable after app backgrounding.

Done criteria:
1. Login Flow v2 path is stable across app lifecycle events.
2. Successful callback results in persisted valid session.

### Task 2.4: Implement manual app-password fallback path
1. Define fallback trigger conditions and UX entrypoint.
2. Validate server, username, and app-password input.
3. Reject main-password-only mode in security policy for v1.

Done criteria:
1. Manual fallback path authenticates correctly with app-password.
2. Fallback errors are clear and non-technical for end users.

### Task 2.5: Implement secure credential vault and session lifecycle
1. Store session tokens in keystore-backed secure storage.
2. Define read, write, refresh, and clear behavior.
3. Ensure logout performs complete credential/session wipe.

Done criteria:
1. Session survives restart when valid.
2. Session is irrecoverable after secure logout.

### Task 2.6: Add auth-specific error handling and guardrails
1. Define error mapping for invalid token, revoked app-password, and unreachable server.
2. Provide 2FA-specific guidance for users who need app-password generation.
3. Prevent repeated silent failures by enforcing explicit user action on persistent auth errors.

Done criteria:
1. All known auth error classes map to user-actionable messages.
2. App does not enter infinite auth retry loops.

### Task 2.7: Add unit, integration, and UI tests for onboarding/auth
1. Test state machine transitions.
2. Test Login Flow v2 callback processing.
3. Test manual fallback validation and secure storage behavior.
4. Test app-start route decisions with valid and invalid sessions.

Done criteria:
1. Critical auth paths are covered by automated tests.
2. Session restore and wipe behavior is verified.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/main/java/com/imagenext/feature/onboarding/WelcomeScreen.kt`.
Purpose: welcome entry screen UI.
2. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/main/java/com/imagenext/feature/onboarding/ServerSetupScreen.kt`.
Purpose: server input and pre-check screen.
3. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/main/java/com/imagenext/feature/onboarding/LoginScreen.kt`.
Purpose: manual fallback login screen.
4. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/main/java/com/imagenext/feature/onboarding/OnboardingViewModel.kt`.
Purpose: onboarding state orchestration.
5. `/Users/alex/Desktop/ImageNext/core/network/src/main/java/com/imagenext/core/network/auth/NextcloudAuthApi.kt`.
Purpose: auth endpoint contracts and request orchestration.
6. `/Users/alex/Desktop/ImageNext/core/network/src/main/java/com/imagenext/core/network/auth/LoginFlowClient.kt`.
Purpose: Login Flow v2 flow manager.
7. `/Users/alex/Desktop/ImageNext/core/security/src/main/java/com/imagenext/core/security/CredentialVault.kt`.
Purpose: keystore-backed secret storage abstraction.
8. `/Users/alex/Desktop/ImageNext/core/security/src/main/java/com/imagenext/core/security/SessionRepositoryImpl.kt`.
Purpose: secure session persistence and wipe implementation.
9. `/Users/alex/Desktop/ImageNext/core/model/src/main/java/com/imagenext/core/model/AuthSession.kt`.
Purpose: authenticated session domain type.
10. `/Users/alex/Desktop/ImageNext/core/model/src/main/java/com/imagenext/core/model/ServerConfig.kt`.
Purpose: normalized server configuration domain type.
11. `/Users/alex/Desktop/ImageNext/app/src/main/java/com/imagenext/app/AppStartRouter.kt`.
Purpose: startup routing between onboarding and main app.
12. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/test/java/...`.
Purpose: onboarding/auth unit tests.
13. `/Users/alex/Desktop/ImageNext/feature/onboarding/src/androidTest/java/...`.
Purpose: onboarding/auth UI tests.

## Verification Checklist
1. Login Flow v2 path succeeds on supported servers.
2. Manual app-password fallback succeeds when selected.
3. Session persistence and secure logout are validated.
4. Error messaging is clear and actionable for non-technical users.

# Phase 6: Albums, Settings, and Security Controls

## Scope
Deliver folder-based albums, user settings management, and visible security controls that keep private data protected.

## Objectives
1. Provide quick album access using selected folder structure.
2. Give users clear controls for account, cache, and sync behavior.
3. Expose security features and diagnostics in a transparent, user-friendly way.

## Implementation Criteria
1. Albums tab reflects selected-folder organization accurately.
2. Settings persist consistently across restarts.
3. Security controls are explicit, auditable, and safe by default.

## Task Breakdown
### Task 6.1: Implement Albums tab from selected folder metadata
1. Define album grouping strategy based on folder hierarchy.
2. Render album covers/count summaries from cached media metadata.
3. Ensure album updates track folder selection changes.

Done criteria:
1. Albums reflect current folder selection state.
2. Album navigation into photo sets is deterministic.

### Task 6.2: Implement Settings tab for account, folders, sync, and cache
1. Define settings sections for account identity, selected folders, sync policy, and cache limits.
2. Define update and persistence rules for each setting.
3. Provide safe defaults and clear explanations for each control.

Done criteria:
1. Settings updates persist and apply correctly.
2. Reset-to-default behaviors are explicit and safe.

### Task 6.3: Implement certificate trust management for self-signed servers
1. Define trust prompt flow with certificate fingerprint confirmation.
2. Persist trusted certificate decisions securely.
3. Provide revocation path for previously trusted certificates.

Done criteria:
1. User must explicitly accept trust decisions.
2. Trust decisions can be reviewed and revoked.

### Task 6.4: Implement optional app lock and secure logout behavior
1. Define app lock policy controls and lifecycle behavior.
2. Define secure logout sequence and post-logout state.
3. Prevent stale session reuse after logout.

Done criteria:
1. Optional lock behavior works as documented.
2. Logout reliably clears all session secrets.

### Task 6.5: Implement local diagnostics with strict redaction
1. Define diagnostics view for sync/auth troubleshooting.
2. Enforce redaction rules for tokens, passwords, and identifiers.
3. Ensure diagnostics remain local by default with no external send path.

Done criteria:
1. Sensitive fields are redacted in all diagnostics outputs.
2. Diagnostics data never leaves device by default.

### Task 6.6: Add settings and security-state test coverage
1. Test setting persistence and lifecycle edge cases.
2. Test certificate trust acceptance and revocation flows.
3. Test logout wipe and app-lock interactions.

Done criteria:
1. Security-critical state transitions are covered by tests.
2. Regressions are detectable via CI pipeline.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/feature/albums/src/main/java/com/imagenext/feature/albums/AlbumsScreen.kt`.
Purpose: Albums tab UI.
2. `/Users/alex/Desktop/ImageNext/feature/albums/src/main/java/com/imagenext/feature/albums/AlbumsViewModel.kt`.
Purpose: Albums state orchestration.
3. `/Users/alex/Desktop/ImageNext/feature/settings/src/main/java/com/imagenext/feature/settings/SettingsScreen.kt`.
Purpose: Settings tab UI.
4. `/Users/alex/Desktop/ImageNext/feature/settings/src/main/java/com/imagenext/feature/settings/SettingsViewModel.kt`.
Purpose: settings state and action orchestration.
5. `/Users/alex/Desktop/ImageNext/feature/settings/src/main/java/com/imagenext/feature/settings/SecuritySettingsScreen.kt`.
Purpose: security controls UI.
6. `/Users/alex/Desktop/ImageNext/core/security/src/main/java/com/imagenext/core/security/CertificateTrustStore.kt`.
Purpose: trusted certificate persistence and lookup.
7. `/Users/alex/Desktop/ImageNext/core/security/src/main/java/com/imagenext/core/security/AppLockManager.kt`.
Purpose: app lock policy abstraction.
8. `/Users/alex/Desktop/ImageNext/core/common/src/main/java/com/imagenext/core/common/RedactingLogger.kt`.
Purpose: standardized redaction-safe logging utility.
9. `/Users/alex/Desktop/ImageNext/feature/settings/src/test/java/...`.
Purpose: settings and security test coverage.

## Verification Checklist
1. Albums align with current selected folders.
2. Settings are persistent and reversible.
3. Security controls and diagnostics redaction work as designed.

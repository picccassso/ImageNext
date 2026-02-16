# ImageNext Android Standalone Viewer Master Plan

Last updated: 2026-02-16

## 1) Product Goals
1. Deliver a fast, modern photo browsing and viewing experience for Nextcloud users on Android.
2. Keep setup friction low with a clear welcome-to-login-to-photos flow.
3. Prioritize security-first handling of credentials, session state, and local media metadata.
4. Ensure progressive loading so the app becomes useful immediately after folder selection.

## 2) Explicit v1 Scope
1. Onboarding flow: Welcome, Server Setup, Login, Folder Selection, Progressive Library Preparation.
2. Main app shell with bottom navigation tabs: `Photos`, `Albums`, `Settings`.
3. Fullscreen viewer with swipe, pinch, zoom, and adjacent image prefetching.
4. Offline-capable metadata and thumbnail cache.
5. Secure logout and token revocation handling.

## 3) Explicit v1 Out of Scope
1. Upload, edit, or delete operations.
2. Share-link creation and collaboration workflows.
3. AI tagging, semantic search, or people recognition.
4. Multi-account switching in one app session.

## 4) Product Defaults and Constraints
1. Product type: standalone viewer-only app for v1.
2. Platform target: Android 9+ (API 28).
3. Authentication default: Nextcloud Browser Login Flow v2 first, manual app-password fallback.
4. Privacy default: no telemetry, analytics, or third-party crash reporting enabled by default.
5. Architecture default: progressive indexing and thumbnail generation in background; no blocking full-scan wall.

## 5) Phase Overview and Exit Criteria
1. Phase 1: Foundation.
Exit criteria: project builds on clean machine, app launches, navigation shell works, dependency versions are pinned, baseline CI is green.
2. Phase 2: Onboarding and Auth.
Exit criteria: user can sign in via Login Flow v2, fallback login works with app password, session securely restores after app restart.
3. Phase 3: Folder Selection and Sync.
Exit criteria: user can select folders, background indexing runs safely, thumbnails appear progressively before full sync completion.
4. Phase 4: Photos Tab.
Exit criteria: timeline/grid is responsive during sync, paging works on large libraries, empty/offline/error states are clear.
5. Phase 5: Fullscreen Viewer.
Exit criteria: viewer gestures are smooth on large images, adjacent prefetch reduces latency, no OOM under stress scenarios.
6. Phase 6: Albums, Settings, Security.
Exit criteria: folder-based albums are functional, user settings persist reliably, security controls are explicit and auditable.
7. Phase 7: Hardening and Release.
Exit criteria: performance budgets pass, security checklist passes, release process is documented and reproducible.

## 6) Architecture Blueprint
### 6.1 Module boundaries
1. `app`: app lifecycle, navigation shell, route wiring.
2. `core/model`: shared domain types.
3. `core/common`: shared utilities and redaction-safe logging primitives.
4. `core/network`: Nextcloud Login Flow and WebDAV client operations.
5. `core/database`: Room entities, DAOs, migrations.
6. `core/security`: keystore-backed secret storage, session lifecycle, certificate trust storage.
7. `core/data`: repository implementations that bridge network, database, and sync layers.
8. `core/sync`: WorkManager orchestration for indexing and thumbnail tasks.
9. `feature/onboarding`, `feature/folders`, `feature/photos`, `feature/viewer`, `feature/albums`, `feature/settings`: UI features and feature-scoped state logic.

### 6.2 Data flow
1. Auth result creates a secure local session.
2. User selects folders to monitor.
3. Sync orchestrator schedules metadata indexing and thumbnail jobs.
4. Database updates stream into UI layers for progressive rendering.
5. Viewer requests full-resolution asset fetch on demand with prefetch for adjacent assets.

## 7) Dependency Baseline (as of 2026-02-16)
1. AGP `9.0.1` and Gradle `9.1.0`.
Reason: current Android build toolchain baseline and compatibility.
2. Kotlin `2.3.10`.
Reason: latest stable language/tooling track aligned with AGP 9.
3. KSP `2.3.4`.
Reason: annotation processing performance and compatibility with Kotlin 2.3.x.
4. Jetpack Compose BOM `2026.01.01`.
Reason: coherent Compose dependency alignment and modern UI APIs.
5. Room `2.8.4`.
Reason: robust local metadata indexing and migration support.
6. WorkManager `2.11.1`.
Reason: resilient background indexing and deferred thumbnail work.
7. DataStore `1.2.0`.
Reason: structured, safe replacement for SharedPreferences use cases.
8. Navigation `2.9.7`.
Reason: stable route management for onboarding and tab navigation.
9. Paging `3.4.1`.
Reason: efficient lazy loading for large image libraries.
10. Lifecycle `2.10.0`.
Reason: stable lifecycle-aware state handling across features.
11. OkHttp `5.3.2` and MockWebServer `5.3.2`.
Reason: modern HTTP stack and reliable network test harness.
12. Coil `3.3.0`.
Reason: modern image loading/caching for Compose-first apps.
13. Coroutines `1.10.2`.
Reason: structured concurrency and reactive flow orchestration.
14. Security dependency policy.
Reason: avoid deprecated `androidx.security:security-crypto` APIs in new credential architecture.

## 8) Security Baseline and Non-Negotiables
1. Enforce HTTPS-only by default; cleartext traffic disabled.
2. Login Flow v2 is the primary authentication path.
3. Manual fallback must use app-password only; never store account main password.
4. Session secrets stored in Android Keystore-backed encrypted vault.
5. Tokens, passwords, and server secrets must never appear in logs.
6. Provide explicit secure logout that wipes local credentials and session artifacts.
7. Self-signed certificate trust requires explicit fingerprint confirmation.
8. App data remains in private app sandbox; sensitive backups disabled.
9. No analytics SDK and no external telemetry by default.
10. Dependency and CVE audit is a release gate, not an optional check.
11. Add network timeout, retry, and lockout-safe behavior to avoid credential leakage through repeated failures.
12. Document incident-response playbook for compromised token handling.

## 9) Public Interfaces and Types to Define
### 9.1 Interfaces
1. `AuthGateway`: Login Flow v2 and manual app-password fallback contract.
2. `SessionRepository`: read, write, refresh, clear session state securely.
3. `FolderRepository`: discover folders, persist selection, query selection state.
4. `LibrarySyncOrchestrator`: schedule, resume, and monitor initial/incremental sync.
5. `MediaRepository`: provide timeline and media detail query contract.
6. `ThumbnailRepository`: thumbnail fetch, cache lookup, cache eviction policy.

### 9.2 Domain types
1. `AuthSession`.
2. `ServerConfig`.
3. `SelectedFolder`.
4. `MediaItem`.
5. `MediaAssetRef`.
6. `SyncState`.
7. `SyncCheckpoint`.

### 9.3 Sync state enum contract
1. `Idle`.
2. `Running`.
3. `Partial`.
4. `Failed`.
5. `Completed`.

## 10) Global Acceptance Targets
1. First usable UI screen visible within 2 seconds on a modern mid-range test device.
2. First thumbnails visible shortly after folder selection without waiting for full scan completion.
3. Photo timeline remains smooth at 10k+ assets.
4. Viewer opens high-resolution images with responsive gestures and no OOM crashes.
5. App restart restores valid session without forced re-login.
6. Security tests verify no credential leakage to logs, backups, or diagnostics exports.

## 11) Test Scenarios (Required)
1. Login Flow v2 success path and persisted session restore.
2. Manual app-password fallback success path.
3. Token revocation and invalid-token recovery flow.
4. Large folder tree selection remains responsive.
5. Sync interruption from network loss resumes without corruption.
6. Timeline behavior with large dataset (10k+ assets).
7. Fullscreen viewer stress with large image files and rapid swipes.
8. Self-signed certificate trust prompt and fingerprint persistence behavior.
9. Secure logout wipes credentials and blocks stale session reuse.
10. Telemetry egress test confirms no outbound analytics endpoints in default config.

## 12) Assumptions and Defaults
1. App name is `ImageNext`.
2. Namespace is `com.imagenext`.
3. Single-account support in v1.
4. Image formats are prioritized; video support is deferred.
5. Bottom navigation remains limited to `Photos`, `Albums`, `Settings` in v1.
6. Progressive indexing is mandatory; blocking full-library scan screens are prohibited.
7. Newer dependency versions may be adopted at implementation time only after compatibility and changelog review.

## 13) Risks and Mitigations
1. Risk: self-hosted Nextcloud instances with nonstandard TLS.
Mitigation: explicit certificate trust flow with fingerprint confirmation and clear warnings.
2. Risk: large libraries causing memory pressure on low-RAM devices.
Mitigation: strict thumbnail sizing, paging, and viewer prefetch throttling.
3. Risk: auth failures in 2FA/OIDC-heavy environments.
Mitigation: browser Login Flow v2 primary path and robust fallback messaging.
4. Risk: slow initial sync causing user abandonment.
Mitigation: progressive loading and immediate access to partially indexed content.

## 14) Documentation Execution Order
1. Execute `/docs/phases/PHASE-1-Foundation.md`.
2. Execute `/docs/phases/PHASE-2-Onboarding-Auth.md`.
3. Execute `/docs/phases/PHASE-3-Folder-Selection-Sync.md`.
4. Execute `/docs/phases/PHASE-4-Photos-Tab.md`.
5. Execute `/docs/phases/PHASE-5-Fullscreen-Viewer.md`.
6. Execute `/docs/phases/PHASE-6-Albums-Settings-Security.md`.
7. Execute `/docs/phases/PHASE-7-Hardening-Release.md`.

## 15) Research References
1. https://developer.android.com/build/releases/gradle-plugin
2. https://developer.android.com/develop/ui/compose/bom
3. https://developer.android.com/jetpack/androidx/versions
4. https://developer.android.com/jetpack/androidx/releases/lifecycle
5. https://developer.android.com/jetpack/androidx/releases/security
6. https://github.com/JetBrains/kotlin/releases/tag/v2.3.10
7. https://github.com/google/ksp/releases
8. https://square.github.io/okhttp/changelogs/changelog/
9. https://coil-kt.github.io/coil/changelog/
10. https://github.com/Kotlin/kotlinx.coroutines/releases
11. https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
12. https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
13. https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html

# Phase 1: Foundation

## Scope
Bootstrap the Android project, establish module boundaries, wire app shell navigation, and lock toolchain/dependency baselines for secure and repeatable builds.

## Objectives
1. Build reproducibility on a clean machine.
2. Clear modular architecture for future phases.
3. Modern navigation shell for `Photos`, `Albums`, and `Settings` placeholders.
4. Security-first default configuration from day one.

## Preconditions
1. Empty or near-empty repository.
2. Android SDK and JDK baseline available in implementation environment.

## Implementation Criteria
1. `./gradlew assembleDebug` succeeds on clean checkout.
2. App launches and shows 3-tab bottom navigation placeholders.
3. Versions are pinned through centralized catalog management.
4. CI runs build, lint, and unit tests successfully.

## Task Breakdown
### Task 1.1: Define module map and package naming conventions
1. Finalize root module list and include statements.
2. Lock namespace standard to `com.imagenext`.
3. Document ownership boundaries between `core/*` and `feature/*` modules.
4. Define dependency direction rule: feature modules depend on core modules, never on each other directly.

Done criteria:
1. Module list is complete and unambiguous.
2. Namespace and dependency rules are documented in this phase file.

### Task 1.2: Configure Gradle, AGP, Kotlin, KSP, and version catalog
1. Pin AGP, Gradle wrapper, Kotlin, KSP, and AndroidX baselines.
2. Configure version catalog aliases for all required dependencies.
3. Set consistent Java/Kotlin compatibility targets.
4. Add baseline build flags for deterministic output and stable CI behavior.

Done criteria:
1. Version catalog is authoritative source of dependency versions.
2. No floating dependency versions remain.

### Task 1.3: Create app entrypoint, navigation shell, and bottom bar skeleton
1. Create application class and main activity host.
2. Create nav host with routes for onboarding and main tabs.
3. Create bottom nav destination model for `Photos`, `Albums`, `Settings`.
4. Ensure launch routing can support future onboarding gating.

Done criteria:
1. App opens without crashes.
2. Three bottom tabs are navigable placeholders.

### Task 1.4: Create design tokens for spacing, typography, color, and motion
1. Define token source for spacing scale, typography scale, color palette, motion durations.
2. Add naming rules for token usage to avoid ad hoc styling in feature screens.
3. Document accessibility baseline for text contrast and touch target sizing.

Done criteria:
1. Token definitions exist and are referenced by shell screens.
2. Accessibility baseline is documented.

### Task 1.5: Add CI build/test/lint pipeline and quality baseline
1. Add CI workflow for build, lint, and unit test.
2. Define baseline static checks and strictness policy.
3. Add module-level test placeholders for immediate next-phase additions.

Done criteria:
1. CI workflow exists and is runnable.
2. Quality gate policy is clear and enforceable.

### Task 1.6: Add security defaults in manifest and network config
1. Disable cleartext traffic globally.
2. Add network security config file and manifest reference.
3. Define backup and debug logging policies for sensitive data handling.

Done criteria:
1. Cleartext traffic is disabled by default.
2. Security defaults are visible in manifest and config docs.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/settings.gradle.kts`.
Purpose: include all core and feature modules.
2. `/Users/alex/Desktop/ImageNext/build.gradle.kts`.
Purpose: root plugin and shared build configuration.
3. `/Users/alex/Desktop/ImageNext/gradle/libs.versions.toml`.
Purpose: centralized pinned dependency versions.
4. `/Users/alex/Desktop/ImageNext/gradle.properties`.
Purpose: global Gradle and Kotlin build flags.
5. `/Users/alex/Desktop/ImageNext/app/build.gradle.kts`.
Purpose: app module wiring and dependencies.
6. `/Users/alex/Desktop/ImageNext/app/src/main/AndroidManifest.xml`.
Purpose: app manifest with security defaults.
7. `/Users/alex/Desktop/ImageNext/app/src/main/java/com/imagenext/app/ImageNextApplication.kt`.
Purpose: application initialization entrypoint.
8. `/Users/alex/Desktop/ImageNext/app/src/main/java/com/imagenext/app/MainActivity.kt`.
Purpose: single-activity host.
9. `/Users/alex/Desktop/ImageNext/app/src/main/java/com/imagenext/navigation/AppNavHost.kt`.
Purpose: route host definition.
10. `/Users/alex/Desktop/ImageNext/app/src/main/java/com/imagenext/navigation/BottomNavDestination.kt`.
Purpose: bottom navigation destination definitions.
11. `/Users/alex/Desktop/ImageNext/app/src/main/res/xml/network_security_config.xml`.
Purpose: TLS and cleartext policy.
12. `/Users/alex/Desktop/ImageNext/core/model/build.gradle.kts`.
Purpose: domain model module configuration.
13. `/Users/alex/Desktop/ImageNext/core/common/build.gradle.kts`.
Purpose: common utilities module configuration.
14. `/Users/alex/Desktop/ImageNext/core/network/build.gradle.kts`.
Purpose: network layer module configuration.
15. `/Users/alex/Desktop/ImageNext/core/database/build.gradle.kts`.
Purpose: database layer module configuration.
16. `/Users/alex/Desktop/ImageNext/core/security/build.gradle.kts`.
Purpose: security layer module configuration.
17. `/Users/alex/Desktop/ImageNext/core/data/build.gradle.kts`.
Purpose: repositories/data orchestration module configuration.
18. `/Users/alex/Desktop/ImageNext/core/sync/build.gradle.kts`.
Purpose: background sync module configuration.
19. `/Users/alex/Desktop/ImageNext/feature/photos/build.gradle.kts`.
Purpose: photos feature module configuration.
20. `/Users/alex/Desktop/ImageNext/feature/albums/build.gradle.kts`.
Purpose: albums feature module configuration.
21. `/Users/alex/Desktop/ImageNext/feature/settings/build.gradle.kts`.
Purpose: settings feature module configuration.
22. `/Users/alex/Desktop/ImageNext/feature/viewer/build.gradle.kts`.
Purpose: fullscreen viewer feature module configuration.
23. `/Users/alex/Desktop/ImageNext/feature/onboarding/build.gradle.kts`.
Purpose: onboarding/auth feature module configuration.
24. `/Users/alex/Desktop/ImageNext/feature/folders/build.gradle.kts`.
Purpose: folder-selection feature module configuration.
25. `/Users/alex/Desktop/ImageNext/.github/workflows/android-ci.yml`.
Purpose: continuous integration workflow.

## Verification Checklist
1. Clean build succeeds locally and in CI.
2. Navigation shell boots and shows 3 tabs.
3. Security defaults are active and documented.
4. Version pinning and module boundaries are complete.

# Phase 5: Fullscreen Viewer

## Scope
Implement the fullscreen viewing experience with smooth gestures, predictable memory behavior, and low-latency adjacent navigation.

## Objectives
1. Deliver a modern and fluid image viewing experience.
2. Keep gesture handling stable across varying image sizes.
3. Reduce wait time between adjacent images via controlled prefetch.

## Implementation Criteria
1. Viewer opens quickly from Photos tab selections.
2. Pinch, zoom, and swipe interactions remain smooth on high-resolution images.
3. Adjacent image prefetch reduces navigation delay without excessive memory pressure.

## Task Breakdown
### Task 5.1: Implement fullscreen route and transition behavior
1. Define viewer route arguments and source-context handling.
2. Define transition rules between grid item and fullscreen state.
3. Ensure back navigation returns user to expected Photos position.

Done criteria:
1. Viewer opens and closes without state loss.
2. Navigation contracts are deterministic.

### Task 5.2: Implement gesture controls and zoom state model
1. Define gesture policy for pinch, pan, double-tap, and swipe.
2. Define zoom bounds and reset behavior.
3. Ensure gesture conflicts are resolved consistently.

Done criteria:
1. Gesture behavior is predictable and testable.
2. No major jitter or stuck states under normal use.

### Task 5.3: Implement adjacent prefetch and cancellation
1. Define prefetch window for previous/next assets.
2. Cancel stale prefetch requests during rapid swipes.
3. Bound prefetch concurrency for low-memory devices.

Done criteria:
1. Adjacent navigation feels immediate in common scenarios.
2. Memory usage remains within safe operating thresholds.

### Task 5.4: Implement EXIF orientation and metadata overlay rules
1. Normalize orientation handling for camera-originated assets.
2. Define optional metadata overlay behavior and toggle state.
3. Ensure metadata display does not block image rendering performance.

Done criteria:
1. Orientation renders correctly for supported sources.
2. Metadata overlay behavior is consistent and non-disruptive.

### Task 5.5: Add stress tests for large image and rapid navigation
1. Test very large image rendering behavior.
2. Test repeated rapid swiping with active prefetch.
3. Test memory pressure handling under prolonged viewer sessions.

Done criteria:
1. Viewer stability validated under stress.
2. OOM and severe frame-drop regressions are detectable.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/feature/viewer/src/main/java/com/imagenext/feature/viewer/ViewerScreen.kt`.
Purpose: fullscreen viewer UI.
2. `/Users/alex/Desktop/ImageNext/feature/viewer/src/main/java/com/imagenext/feature/viewer/ViewerViewModel.kt`.
Purpose: viewer state and interaction orchestration.
3. `/Users/alex/Desktop/ImageNext/feature/viewer/src/main/java/com/imagenext/feature/viewer/ViewerUiModel.kt`.
Purpose: viewer UI state model.
4. `/Users/alex/Desktop/ImageNext/core/data/src/main/java/com/imagenext/core/data/ViewerRepository.kt`.
Purpose: viewer data access contract for current and adjacent assets.
5. `/Users/alex/Desktop/ImageNext/feature/viewer/src/androidTest/java/...`.
Purpose: viewer gesture and performance instrumentation tests.

## Verification Checklist
1. Viewer launch latency meets performance budget.
2. Gesture and navigation behavior remains smooth and predictable.
3. Stress tests confirm no major memory stability regressions.

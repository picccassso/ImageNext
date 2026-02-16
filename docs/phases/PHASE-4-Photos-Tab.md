# Phase 4: Photos Tab

## Scope
Implement the primary Photos experience with timeline/grid browsing, pagination, and resilient loading behavior.

## Objectives
1. Make Photos tab responsive even while background sync is active.
2. Keep memory and network usage predictable on large libraries.
3. Provide clear UX for loading, empty, offline, and error states.

## Implementation Criteria
1. Photos timeline is usable with partial data during active sync.
2. Paging and thumbnail prefetching reduce jank in long scrolling sessions.
3. State restoration preserves user position and view mode expectations.

## Task Breakdown
### Task 4.1: Define timeline query model and grouping strategy
1. Define timeline grouping by date buckets suitable for large datasets.
2. Specify sorting, filtering, and stable key behavior.
3. Define behavior when metadata is incomplete during early sync.

Done criteria:
1. Timeline model is explicit and deterministic.
2. Partial sync data does not break grouping or ordering.

### Task 4.2: Implement Photos screen with high-performance grid
1. Implement grid/timeline rendering strategy based on paging.
2. Add section headers and item placeholders for progressive loading.
3. Ensure smooth updates as new items arrive from sync.

Done criteria:
1. UI remains stable during incremental dataset changes.
2. First visible content appears quickly after tab open.

### Task 4.3: Implement paging and thumbnail prefetch policy
1. Define paging config tuned for large media collections.
2. Define thumbnail prefetch window for near-future grid rows.
3. Prevent excessive prefetching on slow or metered networks.

Done criteria:
1. Scroll performance remains smooth in long sessions.
2. Network and cache usage stay within expected bounds.

### Task 4.4: Implement retry and offline UX behavior
1. Define offline state rendering with clear recovery action.
2. Define error mapping for network failures and stale local state.
3. Support explicit user retry without forcing app restart.

Done criteria:
1. Offline and error states are user-actionable.
2. Retry behavior is deterministic and observable.

### Task 4.5: Add instrumentation tests for performance and state restore
1. Validate state restore after process kill and relaunch.
2. Validate scroll continuity after sync-driven data updates.
3. Validate loading and error state transitions.

Done criteria:
1. Critical Photos behavior is covered by instrumentation tests.
2. Regressions in restore and scroll behavior are detectable in CI.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/feature/photos/src/main/java/com/imagenext/feature/photos/PhotosScreen.kt`.
Purpose: Photos tab UI.
2. `/Users/alex/Desktop/ImageNext/feature/photos/src/main/java/com/imagenext/feature/photos/PhotosViewModel.kt`.
Purpose: Photos state orchestration.
3. `/Users/alex/Desktop/ImageNext/feature/photos/src/main/java/com/imagenext/feature/photos/TimelineUiModel.kt`.
Purpose: timeline UI model and grouping abstraction.
4. `/Users/alex/Desktop/ImageNext/core/data/src/main/java/com/imagenext/core/data/TimelineRepository.kt`.
Purpose: timeline query contract.
5. `/Users/alex/Desktop/ImageNext/core/data/src/main/java/com/imagenext/core/data/ThumbnailRepository.kt`.
Purpose: thumbnail retrieval and cache lifecycle contract.
6. `/Users/alex/Desktop/ImageNext/feature/photos/src/androidTest/java/...`.
Purpose: Photos instrumentation and UI tests.

## Verification Checklist
1. Timeline remains responsive with partial and complete datasets.
2. Scroll performance is stable on large libraries.
3. Offline and error experiences are clear and recoverable.

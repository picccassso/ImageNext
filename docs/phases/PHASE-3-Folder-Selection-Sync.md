# Phase 3: Folder Selection and Background Sync

## Scope
Build folder selection, metadata indexing, and background thumbnail generation with progressive availability in the main app.

## Objectives
1. Let users choose exactly which folders are viewable.
2. Build reliable initial and incremental sync behavior.
3. Surface content progressively as indexing proceeds.

## Implementation Criteria
1. Folder tree discovery and selection are stable and performant.
2. Selected folders persist across app restarts.
3. Metadata indexing is resumable and safe under network interruptions.
4. Thumbnails appear incrementally without waiting for full sync completion.

## Task Breakdown
### Task 3.1: Implement WebDAV folder discovery and filtering
1. Integrate folder enumeration for user-accessible directory trees.
2. Filter for selectable locations relevant to media viewing.
3. Define safeguards against extremely deep or malformed trees.

Done criteria:
1. Folder list loads for large trees with bounded response handling.
2. Unselectable or invalid entries are consistently excluded.

### Task 3.2: Build folder selection UI with search and persistence
1. Provide searchable folder selection interface.
2. Persist selected folder identifiers locally.
3. Handle add/remove updates safely after initial setup.

Done criteria:
1. Selected folders remain stable after restart.
2. Folder selection changes trigger sync updates deterministically.

### Task 3.3: Define Room schema for metadata and sync checkpoints
1. Define entities for media items, selected folders, and sync checkpoints.
2. Define DAOs for timeline and folder query patterns.
3. Define migration policy for schema evolution.

Done criteria:
1. Schema supports timeline and viewer requirements.
2. Checkpointing supports safe incremental sync continuation.

### Task 3.4: Implement initial indexing and incremental sync strategy
1. Define first-run indexing flow.
2. Define periodic/incremental sync flow keyed by checkpoints.
3. Define retry, backoff, and conflict-safe update behavior.

Done criteria:
1. Initial indexing is reliable under unstable networks.
2. Incremental sync updates only changed assets where possible.

### Task 3.5: Implement thumbnail queue and cache lifecycle
1. Define thumbnail generation/fetch queue policy.
2. Define cache write/read/eviction behavior.
3. Prevent duplicate thumbnail jobs and unnecessary network churn.

Done criteria:
1. Thumbnail jobs are deduplicated and recoverable.
2. Cache growth remains bounded by policy.

### Task 3.6: Implement sync progress model for UI
1. Expose sync states and progress for UI consumption.
2. Support transitions for running, partial completion, and failures.
3. Ensure UI remains usable while sync proceeds.

Done criteria:
1. UI reflects progress accurately.
2. Failure states provide user-actionable retry options.

### Task 3.7: Add sync reliability tests with mock network
1. Test first-run indexing.
2. Test interrupted sync resumption.
3. Test checkpoint correctness and duplication prevention.
4. Test thumbnail queue behavior under load.

Done criteria:
1. Critical sync invariants are covered by automated tests.
2. No data corruption in interruption/retry scenarios.

## Files to Create and Purpose
1. `/Users/alex/Desktop/ImageNext/feature/folders/src/main/java/com/imagenext/feature/folders/FolderSelectionScreen.kt`.
Purpose: folder picker UI.
2. `/Users/alex/Desktop/ImageNext/feature/folders/src/main/java/com/imagenext/feature/folders/FolderSelectionViewModel.kt`.
Purpose: folder selection state and action orchestration.
3. `/Users/alex/Desktop/ImageNext/core/network/src/main/java/com/imagenext/core/network/webdav/WebDavClient.kt`.
Purpose: WebDAV operations for folder and metadata retrieval.
4. `/Users/alex/Desktop/ImageNext/core/model/src/main/java/com/imagenext/core/model/SelectedFolder.kt`.
Purpose: selected folder domain model.
5. `/Users/alex/Desktop/ImageNext/core/model/src/main/java/com/imagenext/core/model/SyncState.kt`.
Purpose: sync state contract for UI and orchestration.
6. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/AppDatabase.kt`.
Purpose: Room database root and migration registration.
7. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/entity/MediaItemEntity.kt`.
Purpose: local media metadata storage model.
8. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/entity/SelectedFolderEntity.kt`.
Purpose: persisted folder selection model.
9. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/entity/SyncCheckpointEntity.kt`.
Purpose: sync continuity checkpoint model.
10. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/dao/MediaDao.kt`.
Purpose: media query interface for timeline and viewer.
11. `/Users/alex/Desktop/ImageNext/core/database/src/main/java/com/imagenext/core/database/dao/FolderDao.kt`.
Purpose: selected folder query and update operations.
12. `/Users/alex/Desktop/ImageNext/core/data/src/main/java/com/imagenext/core/data/FolderRepositoryImpl.kt`.
Purpose: folder discovery/selection repository implementation.
13. `/Users/alex/Desktop/ImageNext/core/data/src/main/java/com/imagenext/core/data/MediaRepositoryImpl.kt`.
Purpose: media metadata repository implementation.
14. `/Users/alex/Desktop/ImageNext/core/sync/src/main/java/com/imagenext/core/sync/LibrarySyncWorker.kt`.
Purpose: background metadata indexing worker.
15. `/Users/alex/Desktop/ImageNext/core/sync/src/main/java/com/imagenext/core/sync/ThumbnailWorker.kt`.
Purpose: background thumbnail generation and fetch worker.
16. `/Users/alex/Desktop/ImageNext/core/sync/src/main/java/com/imagenext/core/sync/SyncOrchestrator.kt`.
Purpose: sync scheduling and policy coordinator.
17. `/Users/alex/Desktop/ImageNext/core/sync/src/test/java/...`.
Purpose: sync reliability test coverage.

## Verification Checklist
1. Folder discovery handles large structures safely.
2. Selection persistence and updates are stable.
3. Sync resumes correctly after interruption.
4. Thumbnails appear progressively during active sync.

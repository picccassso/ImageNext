package com.imagenext.core.model

/**
 * Sync state contract for UI and orchestration.
 *
 * Defines the possible states of a sync operation as specified
 * in MASTERPLAN §9.3.
 */
enum class SyncState {
    /** No sync operation is active or pending. */
    Idle,

    /** A sync operation is actively running. */
    Running,

    /** Sync partially completed (some folders synced, others pending or failed). */
    Partial,

    /** Sync operation failed and requires retry. */
    Failed,

    /** Sync operation completed successfully for all selected folders. */
    Completed,
}

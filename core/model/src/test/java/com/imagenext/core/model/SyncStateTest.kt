package com.imagenext.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateTest {

    @Test
    fun `SyncState has exactly 5 values`() {
        assertEquals(5, SyncState.entries.size)
    }

    @Test
    fun `SyncState contains all expected values`() {
        val expected = setOf("Idle", "Running", "Partial", "Failed", "Completed")
        val actual = SyncState.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `SyncState valueOf works for all values`() {
        assertTrue(SyncState.valueOf("Idle") == SyncState.Idle)
        assertTrue(SyncState.valueOf("Running") == SyncState.Running)
        assertTrue(SyncState.valueOf("Partial") == SyncState.Partial)
        assertTrue(SyncState.valueOf("Failed") == SyncState.Failed)
        assertTrue(SyncState.valueOf("Completed") == SyncState.Completed)
    }

    @Test
    fun `SyncState name returns expected string`() {
        assertEquals("Idle", SyncState.Idle.name)
        assertEquals("Running", SyncState.Running.name)
        assertEquals("Partial", SyncState.Partial.name)
        assertEquals("Failed", SyncState.Failed.name)
        assertEquals("Completed", SyncState.Completed.name)
    }
}

package com.pocketos.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEventStoreTest {
    @Test
    fun recordsBoundedEnumOnlyEventsAcrossStoreInstances() {
        val directory = createTempDir()
        try {
            val file = File(directory, "events.log")
            val store = DiagnosticsEventStore(file)
            store.record(DiagnosticEventType.START_REQUESTED, VmState.STOPPED)
            store.record(DiagnosticEventType.STATE_CHANGED, VmState.RUNNING)

            val events = DiagnosticsEventStore(file).readLatest()
            assertEquals(2, events.size)
            assertEquals(DiagnosticEventType.START_REQUESTED, events[0].type)
            assertEquals(VmState.RUNNING, events[1].state)
            assertTrue(events[0].timestampMs > 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedOrInjectedLinesAreIgnored() {
        val directory = createTempDir()
        try {
            val file = File(directory, "events.log")
            file.writeText("1|STATE_CHANGED|RUNNING|password=hunter2\n")
            val events = DiagnosticsEventStore(file).readLatest()
            assertTrue(events.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun retainsOnlyTheMostRecentEvents() {
        val directory = createTempDir()
        try {
            val file = File(directory, "events.log")
            val store = DiagnosticsEventStore(file)
            repeat(DiagnosticsEventStore.MAX_EVENTS + 3) {
                store.record(DiagnosticEventType.STATE_CHANGED, VmState.STOPPED)
            }
            assertEquals(DiagnosticsEventStore.MAX_EVENTS, store.readLatest().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportedEventSummaryContainsNoUntrustedDetails() {
        val event = PocketOsDiagnosticEvent(
            timestampMs = 1_800_000_000_000,
            type = DiagnosticEventType.STATE_CHANGED,
            state = VmState.FAILED,
            stopReason = GuestStopReason.START_FAILURE,
        )
        val summary = event.safeSummary()
        assertTrue(summary.contains("STATE_CHANGED"))
        assertFalse(summary.contains("password"))
        assertFalse(summary.contains("terminal"))
    }
}

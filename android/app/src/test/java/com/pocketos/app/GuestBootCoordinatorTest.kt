package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestBootCoordinatorTest {
    @Test
    fun processStartDoesNotMarkRunningUntilGuestIsReadyAndStartsDeadlineAtReadiness() {
        val lifecycle = VmLifecycle()
        val store = FakeStore()
        val clock = FakeClock(1_000)
        val coordinator = GuestBootCoordinator(
            lifecycle,
            GuestReadinessProbe(),
            SessionProtection(store, clock),
        )

        assertTrue(coordinator.beginPreparation())
        assertTrue(coordinator.onPreparationComplete())
        assertTrue(coordinator.onProcessStarted())
        assertEquals(VmState.BOOTING, lifecycle.state)
        assertNull(store.checkpoint)

        clock.now = 4_000
        assertEquals(
            GuestBootEvent.NO_CHANGE,
            coordinator.onSerialOutput("kernel starting\r\n".toByteArray(), 80),
        )
        assertEquals(VmState.BOOTING, lifecycle.state)
        assertEquals(
            GuestBootEvent.READY,
            coordinator.onSerialOutput("POCKETOS_FIRST_BOOT: set a password for account pocketos\\r\\n".toByteArray(), 80),
        )
        assertEquals(VmState.RUNNING, lifecycle.state)
        assertEquals(4_000L, store.checkpoint?.startedAtElapsedMs)
    }

    @Test
    fun rejectsGuestReadinessWhenBatteryIsAtCutoff() {
        val lifecycle = VmLifecycle()
        val store = FakeStore()
        val coordinator = GuestBootCoordinator(
            lifecycle,
            GuestReadinessProbe(),
            SessionProtection(store, FakeClock(10)),
        )
        coordinator.beginPreparation()
        coordinator.onPreparationComplete()
        coordinator.onProcessStarted()

        assertEquals(
            GuestBootEvent.LOW_BATTERY_REJECTED,
            coordinator.onSerialOutput("pocketos login: ".toByteArray(), 20),
        )
        assertEquals(VmState.FAILED, lifecycle.state)
        assertNull(store.checkpoint)
    }

    @Test
    fun requestsTimerShutdownAndClearsCheckpointAfterCleanExit() {
        val lifecycle = VmLifecycle()
        val store = FakeStore()
        val coordinator = GuestBootCoordinator(
            lifecycle,
            GuestReadinessProbe(),
            SessionProtection(store, FakeClock(100)),
        )
        coordinator.beginPreparation()
        coordinator.onPreparationComplete()
        coordinator.onProcessStarted()
        assertEquals(
            GuestBootEvent.READY,
            coordinator.onSerialOutput("pocketos login: ".toByteArray(), 80),
        )

        assertTrue(coordinator.requestShutdown())
        assertTrue(lifecycle.transitionTo(VmState.STOPPING))
        assertTrue(coordinator.onProcessExited())
        assertEquals(VmState.STOPPED, lifecycle.state)
        assertNull(store.checkpoint)
    }

    @Test
    fun unexpectedProcessExitFailsAndClearsSession() {
        val lifecycle = VmLifecycle()
        val store = FakeStore()
        val coordinator = GuestBootCoordinator(
            lifecycle,
            GuestReadinessProbe(),
            SessionProtection(store, FakeClock(100)),
        )
        coordinator.beginPreparation()
        coordinator.onPreparationComplete()
        coordinator.onProcessStarted()
        coordinator.onSerialOutput("pocketos login: ".toByteArray(), 80)

        assertTrue(coordinator.onProcessExited())
        assertEquals(VmState.FAILED, lifecycle.state)
        assertNull(store.checkpoint)
        assertFalse(coordinator.requestShutdown())
    }

    private class FakeStore(var checkpoint: SessionCheckpoint? = null) : SessionCheckpointStore {
        override fun load() = checkpoint
        override fun save(checkpoint: SessionCheckpoint) { this.checkpoint = checkpoint }
        override fun clear() { checkpoint = null }
    }

    private class FakeClock(var now: Long) : ElapsedRealtimeClock {
        override fun nowMs() = now
    }
}

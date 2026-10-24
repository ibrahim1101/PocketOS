package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProtectionTest {
    @Test
    fun startsDeadlineOnlyAboveBatteryCutoffAndRestoresAfterProcessRecreation() {
        val store = FakeStore()
        val clock = FakeClock(10_000)
        val protection = SessionProtection(store, clock)

        assertFalse(protection.startWhenRunning(20))
        assertNull(store.checkpoint)
        assertTrue(protection.startWhenRunning(21))
        assertEquals(10_000 + SessionPolicy.MAX_SESSION_MS, store.checkpoint?.deadlineElapsedMs)

        clock.now = 10_000 + 15 * 60 * 1000
        val recreated = SessionProtection(store, clock)
        assertTrue(recreated.startWhenRunning(80))
        assertEquals(10_000 + SessionPolicy.MAX_SESSION_MS, store.checkpoint?.deadlineElapsedMs)
        assertEquals(45 * 60 * 1000L, recreated.remainingMs())
        assertNull(recreated.stopReason(80))
    }

    @Test
    fun stopsAtTimerAndBatteryThreshold() {
        val store = FakeStore()
        val clock = FakeClock(100)
        val protection = SessionProtection(store, clock)
        assertTrue(protection.startWhenRunning(80))

        clock.now = 100 + SessionPolicy.MAX_SESSION_MS
        assertEquals(SessionStopReason.TIME_LIMIT, protection.stopReason(80))
        assertEquals(SessionStopReason.BATTERY_CUTOFF, protection.stopReason(20))
    }

    @Test
    fun expiresCheckpointAfterDeviceRebootInsteadOfGrantingFreshSession() {
        val store = FakeStore(SessionCheckpoint(50_000, 50_000 + SessionPolicy.MAX_SESSION_MS))
        val protection = SessionProtection(store, FakeClock(5_000))

        assertEquals(SessionStopReason.DEVICE_REBOOT, protection.stopReason(90))
        assertEquals(0L, protection.remainingMs())
    }

    @Test
    fun clearingCheckpointEndsSession() {
        val store = FakeStore()
        val protection = SessionProtection(store, FakeClock(10))
        assertTrue(protection.startWhenRunning(90))
        protection.clear()
        assertNull(protection.remainingMs())
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

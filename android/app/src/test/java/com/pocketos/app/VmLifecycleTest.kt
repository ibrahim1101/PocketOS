package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class VmLifecycleTest {
    @Test
    fun followsGuestReadinessBeforeRunning() {
        val lifecycle = VmLifecycle()

        assertTrue(lifecycle.canStart())
        assertTrue(lifecycle.transitionTo(VmState.PREPARING))
        assertTrue(lifecycle.transitionTo(VmState.STARTING))
        assertFalse(lifecycle.transitionTo(VmState.RUNNING))
        assertTrue(lifecycle.transitionTo(VmState.BOOTING))
        assertTrue(lifecycle.transitionTo(VmState.RUNNING))
        assertEquals(VmState.RUNNING, lifecycle.state)
        assertFalse(lifecycle.canStart())
    }

    @Test
    fun rejectsDuplicateAndOutOfOrderEvents() {
        val lifecycle = VmLifecycle()

        assertFalse(lifecycle.transitionTo(VmState.PREPARING))
        assertTrue(lifecycle.transitionTo(VmState.PREPARING))
        assertFalse(lifecycle.transitionTo(VmState.PREPARING))
        assertFalse(lifecycle.transitionTo(VmState.RUNNING))
        assertEquals(VmState.PREPARING, lifecycle.state)
    }

    @Test
    fun supportsGracefulStopAndFailureRecovery() {
        val lifecycle = VmLifecycle()
        assertTrue(lifecycle.transitionTo(VmState.PREPARING))
        assertTrue(lifecycle.transitionTo(VmState.STARTING))
        assertTrue(lifecycle.transitionTo(VmState.BOOTING))
        assertTrue(lifecycle.transitionTo(VmState.RUNNING))
        assertTrue(lifecycle.transitionTo(VmState.SHUTDOWN_REQUESTED))
        assertTrue(lifecycle.transitionTo(VmState.STOPPING))
        assertTrue(lifecycle.transitionTo(VmState.STOPPED))
        assertTrue(lifecycle.canStart())

        assertTrue(lifecycle.transitionTo(VmState.PREPARING))
        assertTrue(lifecycle.transitionTo(VmState.FAILED))
        assertTrue(lifecycle.transitionTo(VmState.RECOVERING))
        assertTrue(lifecycle.transitionTo(VmState.STOPPED))
    }

    @Test
    fun allowsCancellationDuringStartup() {
        val lifecycle = VmLifecycle()
        assertTrue(lifecycle.transitionTo(VmState.PREPARING))
        assertTrue(lifecycle.transitionTo(VmState.STOPPING))
        assertTrue(lifecycle.transitionTo(VmState.STOPPED))
    }

    @Test
    fun serializesConcurrentStartRequests() {
        val lifecycle = VmLifecycle()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()

        val workers = (0 until 8).map {
            Thread {
                ready.countDown()
                start.await()
                if (lifecycle.transitionTo(VmState.PREPARING)) {
                    accepted.incrementAndGet()
                }
            }.apply { start() }
        }

        ready.await()
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, accepted.get())
        assertEquals(VmState.PREPARING, lifecycle.state)
    }
}

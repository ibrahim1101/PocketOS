package com.pocketos.app

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSessionControllerTest {
    @Test
    fun rejectsLowBatteryBeforePreparingOrLaunching() {
        val rig = Rig(initialBattery = 20)
        var prepared = false

        assertFalse(rig.controller.start {
            prepared = true
            rig.preparedGuest()
        })
        assertFalse(prepared)
        assertEquals(VmState.STOPPED, rig.controller.state)
        assertNull(rig.launcher.process)
    }

    @Test
    fun processStaysBootingUntilReadinessAndDeadlineStartsAtReadiness() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        assertEquals(VmState.BOOTING, rig.controller.state)
        assertTrue(rig.scheduler.tasks.isEmpty())
        assertNull(rig.store.checkpoint)

        rig.clock.now = 4_000
        rig.launcher.emitSerial("kernel starting\r\n")
        assertEquals(VmState.BOOTING, rig.controller.state)
        rig.launcher.emitSerial("New password:\r\n")

        assertEquals(VmState.RUNNING, rig.controller.state)
        assertEquals(4_000L, rig.store.checkpoint?.startedAtElapsedMs)
        assertEquals(SessionPolicy.MAX_SESSION_MS, rig.scheduler.tasks.single().delayMs)
    }

    @Test
    fun deadlineRequestsGracefulShutdownBeforeReleasingDiskLease() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        val task = rig.scheduler.tasks.single()
        assertTrue(rig.controller.writeSerial("whoami\n".toByteArray()))

        task.run()

        assertEquals(1, rig.gracefulShutdownRequests)
        assertEquals(1, rig.launcher.process?.expectOutputClosureCount)
        assertEquals(VmState.STOPPED, rig.controller.state)
        assertEquals(GuestStopReason.TIME_LIMIT, rig.controller.lastStopReason)
        assertNull(rig.store.checkpoint)
        assertTrue(rig.lease.closed)
    }

    @Test
    fun batteryCutoffStopsRunningGuestAndRecordsReason() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")

        assertTrue(rig.controller.onBatteryChanged(20))
        assertEquals(VmState.STOPPED, rig.controller.state)
        assertEquals(GuestStopReason.BATTERY_CUTOFF, rig.controller.lastStopReason)
        assertTrue(rig.lease.closed)
    }

    @Test
    fun unexpectedExitFailsSessionAndReleasesDiskLease() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        rig.launcher.process?.exit()

        awaitState(rig.controller, VmState.FAILED)
        assertEquals(GuestStopReason.UNEXPECTED_EXIT, rig.controller.lastStopReason)
        assertNull(rig.store.checkpoint)
        assertTrue(rig.lease.closed)
    }

    @Test
    fun preparationAndProcessLaunchFailuresLeaveNoLeaseOrCheckpoint() {
        val prepareRig = Rig()
        assertFalse(prepareRig.controller.start { error("disk install failed") })
        assertEquals(VmState.FAILED, prepareRig.controller.state)

        val launchRig = Rig()
        launchRig.launcher.failToStart = true
        assertFalse(launchRig.controller.start { launchRig.preparedGuest() })
        assertEquals(VmState.FAILED, launchRig.controller.state)
        assertTrue(launchRig.lease.closed)
        assertNull(launchRig.store.checkpoint)
    }

    @Test
    fun repeatedStartCannotCreateADuplicateProcess() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        var preparedAgain = false

        assertFalse(rig.controller.start {
            preparedAgain = true
            rig.preparedGuest()
        })
        assertFalse(preparedAgain)
        assertEquals(1, rig.launcher.launchCount)
    }

    @Test
    fun forceStopsOnlyAfterGracefulShutdownTimesOut() {
        val rig = Rig(gracefulShutdownCompletes = false)
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")

        assertTrue(rig.controller.stopByUser())

        assertEquals(1, rig.gracefulShutdownRequests)
        assertEquals(1, rig.launcher.process?.expectOutputClosureCount)
        assertEquals(1, rig.launcher.process?.forceStopCount)
        assertEquals(VmState.STOPPED, rig.controller.state)
        assertTrue(rig.lease.closed)
    }

    private fun awaitState(controller: GuestSessionController, expected: VmState) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (controller.state != expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, controller.state)
    }

    private class Rig(initialBattery: Int = 80, private val gracefulShutdownCompletes: Boolean = true) {
        val lifecycle = VmLifecycle()
        val store = FakeStore()
        val clock = FakeClock(1_000)
        val coordinator = GuestBootCoordinator(lifecycle, GuestReadinessProbe(), SessionProtection(store, clock))
        val launcher = FakeLauncher()
        val scheduler = FakeScheduler()
        val lease = FakeLease()
        var battery = initialBattery
        var gracefulShutdownRequests = 0
        val controller = GuestSessionController(
            lifecycle = lifecycle,
            coordinator = coordinator,
            processLauncher = launcher,
            scheduler = scheduler,
            batteryPercent = { battery },
            gracefulShutdownTimeoutMs = 1,
            forceStopTimeoutMs = 1,
        )

        fun preparedGuest() = PreparedGuest(
            command = listOf("qemu-system-aarch64"),
            workingDirectory = File("/data/local/tmp"),
            diskLease = lease,
            requestGracefulShutdown = {
                gracefulShutdownRequests += 1
                if (gracefulShutdownCompletes) launcher.process?.exit()
            },
        )
    }

    private class FakeLauncher : GuestProcessLauncher {
        var process: FakeProcess? = null
        var launchCount = 0
        var failToStart = false
        private var serial: (ByteArray) -> Unit = {}
        override fun start(
            command: List<String>,
            workingDirectory: File?,
            onSerialOutput: (ByteArray) -> Unit,
            onDiagnosticOutput: (ByteArray) -> Unit,
        ): GuestProcess {
            launchCount += 1
            check(!failToStart) { "process start failed" }
            serial = onSerialOutput
            process = FakeProcess()
            return process!!
        }

        fun emitSerial(output: String) = serial(output.toByteArray())
    }

    private class FakeProcess : GuestProcess {
        private val monitor = java.lang.Object()
        @Volatile
        private var running = true
        override val isAlive: Boolean get() = running

        override fun writeSerial(bytes: ByteArray) {
            check(running)
        }

        var expectOutputClosureCount = 0
        override fun expectOutputClosure() {
            expectOutputClosureCount += 1
        }

        override fun awaitExit(timeout: Long, unit: TimeUnit): Boolean {
            synchronized(monitor) {
                if (running) unit.timedWait(monitor, timeout)
                return !running
            }
        }

        var forceStopCount = 0
        override fun forceStop(timeout: Long, unit: TimeUnit): Boolean {
            forceStopCount += 1
            exit()
            return true
        }

        fun exit() {
            synchronized(monitor) {
                running = false
                monitor.notifyAll()
            }
        }
    }

    private class FakeScheduler : SessionTaskScheduler {
        val tasks = mutableListOf<FakeTask>()
        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledSessionTask {
            val scheduled = FakeTask(delayMs, task)
            tasks += scheduled
            return scheduled
        }
    }

    private class FakeTask(val delayMs: Long, private val task: () -> Unit) : ScheduledSessionTask {
        private var cancelled = false
        fun run() { if (!cancelled) task() }
        override fun cancel() { cancelled = true }
    }

    private class FakeLease : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
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

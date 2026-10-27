package com.pocketos.app

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSessionControllerTest {
    @Test
    fun restartedProcessMustReachItsOwnPromptBeforeBecomingRunning() {
        val rig = Rig()
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        assertEquals(VmState.RUNNING, rig.controller.state)
        val previousSerial = rig.launcher.serialReceiver()
        val previousDeadline = rig.scheduler.tasks.single()
        assertTrue(rig.controller.stopByUser())
        assertTrue(rig.controller.start { rig.preparedGuest().copy(diskLease = FakeLease()) })
        try {
            rig.launcher.emitSerial("Booting Linux\r\n")
            assertEquals(VmState.BOOTING, rig.controller.state)
            previousSerial("pocketos login: ".toByteArray())
            assertEquals("Old reader marked replacement guest ready", VmState.BOOTING, rig.controller.state)
            assertFalse(rig.controller.writeSerial("premature-input\n".toByteArray()))
            rig.launcher.emitSerial("pocketos login: ")
            assertEquals(VmState.RUNNING, rig.controller.state)
            previousDeadline.runEvenIfCancelled()
            assertEquals("Old deadline stopped replacement guest", VmState.RUNNING, rig.controller.state)
            assertTrue(rig.launcher.process!!.isAlive)
        } finally { rig.controller.stopByUser() }
    }

    @Test(timeout = 5000)
    fun lateTerminalFailureCannotStopReplacementGuest() {
        val failureHandled = CountDownLatch(1)
        val rig = Rig(serialWriteTimeoutMs = 2000, diagnostic = { failureHandled.countDown() })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        rig.launcher.process!!.writeAction = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        val writer = Thread { rig.controller.writeSerial("old-input\n".toByteArray()) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(rig.controller.stopByUser())
            assertTrue(rig.controller.start { rig.preparedGuest().copy(diskLease = FakeLease()) })
            rig.launcher.emitSerial("pocketos login: ")
            val replacement = rig.launcher.process!!
            release.countDown()
            writer.join(1000)
            assertTrue("Late failure cleanup never completed", failureHandled.await(1, TimeUnit.SECONDS))
            assertEquals(VmState.RUNNING, rig.controller.state)
            assertTrue(replacement.isAlive)
            assertNull(rig.controller.lastStopReason)
        } finally {
            release.countDown()
            rig.controller.stopByUser()
            writer.join(1000)
        }
    }

    @Test(timeout = 5000)
    fun blockedTerminalWriteDoesNotHoldLifecycleLockAgainstBatteryCutoff() {
        val rig = Rig(serialWriteTimeoutMs = 2000)
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.launcher.process!!.writeAction = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        val writer = Thread { rig.controller.writeSerial("test\n".toByteArray()) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertTrue(rig.controller.onBatteryChanged(20))
            assertTrue("Battery cutoff waited behind terminal I/O", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
            assertEquals(GuestStopReason.BATTERY_CUTOFF, rig.controller.lastStopReason)
            assertTrue(rig.lease.closed)
            assertFalse(rig.launcher.process!!.isAlive)
        } finally {
            release.countDown()
            writer.join(1000)
        }
    }

    @Test(timeout = 5000)
    fun terminalWriteDeadlineStopsGuestWithoutWaitingForPipeToDrain() {
        val rig = Rig(serialWriteTimeoutMs = 50)
        assertTrue(rig.controller.start { rig.preparedGuest() })
        rig.launcher.emitSerial("pocketos login: ")
        val release = CountDownLatch(1)
        rig.launcher.process!!.writeAction = { release.await(3, TimeUnit.SECONDS) }
        try {
            assertFalse(rig.controller.writeSerial("test\n".toByteArray()))
            awaitState(rig.controller, VmState.STOPPED)
            assertEquals(GuestStopReason.TERMINAL_IO_FAILURE, rig.controller.lastStopReason)
            assertTrue(rig.lease.closed)
        } finally { release.countDown() }
    }

    @Test(timeout = 5000)
    fun hungGracefulRequestCannotPreventForceStopAndDiskRelease() {
        val rig = Rig(gracefulShutdownCompletes = false)
        val release = CountDownLatch(1)
        assertTrue(rig.controller.start { rig.preparedGuest().copy(requestGracefulShutdown = { release.await(3, TimeUnit.SECONDS) }) })
        rig.launcher.emitSerial("pocketos login: ")
        try {
            val started = System.nanoTime()
            assertTrue(rig.controller.stopByUser())
            assertTrue("Stuck callback prevented bounded shutdown", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            assertEquals(1, rig.launcher.process!!.forceStopCount)
            assertEquals(VmState.STOPPED, rig.controller.state)
            assertTrue(rig.lease.closed)
        } finally { release.countDown() }
    }

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
    fun stalledBootIsStoppedByIndependentDeadlineBeforeReadiness() {
        val rig = Rig(bootTimeoutMs = 100)
        assertTrue(rig.controller.start { rig.preparedGuest() })

        awaitState(rig.controller, VmState.STOPPED)

        assertEquals(GuestStopReason.BOOT_TIMEOUT, rig.controller.lastStopReason)
        assertEquals(1, rig.gracefulShutdownRequests)
        assertTrue("Stalled guest process survived its boot deadline", rig.launcher.process?.isAlive == false)
        assertTrue("Boot timeout did not release its disk lease", rig.lease.closed)
        assertNull(rig.store.checkpoint)
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

    private class Rig(
        initialBattery: Int = 80,
        private val gracefulShutdownCompletes: Boolean = true,
        bootTimeoutMs: Long = GuestSessionController.DEFAULT_BOOT_TIMEOUT_MS,
        serialWriteTimeoutMs: Long = GuestSessionController.DEFAULT_SERIAL_WRITE_TIMEOUT_MS,
        diagnostic: (ByteArray) -> Unit = {},
    ) {
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
            gracefulShutdownTimeoutMs = 100,
            forceStopTimeoutMs = 1,
            bootTimeoutMs = bootTimeoutMs,
            serialWriteTimeoutMs = serialWriteTimeoutMs,
            onDiagnosticOutput = diagnostic,
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
        fun serialReceiver(): (ByteArray) -> Unit = serial
    }

    private class FakeProcess : GuestProcess {
        private val monitor = java.lang.Object()
        @Volatile
        private var running = true
        override val isAlive: Boolean get() = running

        override fun writeSerial(bytes: ByteArray) {
            writeAction()
            check(running)
        }

        var writeAction: () -> Unit = {}

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
        fun runEvenIfCancelled() { task() }
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


package com.pocketos.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class QemuAndroidRuntimeInstrumentationTest {
    @Test
    fun cpuExecutionLeaseKeepsCpuAwakeUntilClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lease = CpuExecutionLease(context, "$TAG:wake-lock-test", fallbackTimeoutMs = 5_000)
        try {
            lease.acquire()
            assertTrue("CPU execution lease did not acquire its partial wake lock", lease.isHeld)
        } finally {
            lease.close()
        }
        assertFalse("CPU execution lease did not release its partial wake lock", lease.isHeld)
    }

    @Volatile
    private var activeJournal: RuntimeTestJournal? = null

    @Volatile
    private var activeStage: String = "not-started"

    @Test
    fun failedGuestBootTimesOutWithDiagnosticsAndCleanupPreservesFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val socket = File(context.cacheDir, "qmp-failed-boot-${UUID.randomUUID()}.sock")
        val console = SerialCapture("ephemeral-test-secret", socket)
        val stderr = BoundedDiagnosticCapture()
        val process = NativeProcessSupervisor().start(
            command = listOf("/system/bin/sh", "-c", "printf 'POCKETOS_GUEST_BOOT_STARTED\\n'; exec sleep 30"),
            onSerialOutput = console::receive,
            onDiagnosticOutput = stderr::append,
        )

        val failure = runCatching {
            try {
                awaitStage("Waiting for an intentionally absent guest prompt", "POCKETOS_PROMPT_NOT_PRESENT", console, process, stderr, 500)
            } finally {
                stopIfStillRunning("failed guest boot regression", process, console, stderr)
                runCatching { socket.delete() }
            }
        }.exceptionOrNull()

        assertNotNull("Boot timeout was lost during cleanup", failure)
        assertTrue("Cleanup masked the original bounded boot failure: $failure", failure is AssertionError)
        val diagnostic = failure!!.message.orEmpty()
        assertTrue("Failure did not identify the failed boot stage", diagnostic.contains("Timed out: Waiting for an intentionally absent guest prompt"))
        assertTrue(
            "Failure did not include process state",
            diagnostic.contains("exitStatus=running") || diagnostic.contains("exitStatus=exited("),
        )
        assertTrue("Failure did not include the serial tail", diagnostic.contains("POCKETOS_GUEST_BOOT_STARTED"))
        assertTrue("Failure did not include QMP connectivity", diagnostic.contains("qmp=unavailable"))
        assertTrue("Process remained alive after bounded cleanup", !process.isAlive)
        assertTrue("Output pumps did not finish after bounded cleanup", process.awaitOutputPumps(5, TimeUnit.SECONDS))
    }

    @Test
    fun independentWatchdogKillsStalledProcessWhileTestThreadIsBlocked() {
        val output = BoundedDiagnosticCapture()
        val process = NativeProcessSupervisor().start(
            command = listOf("/system/bin/sh", "-c", "exec sleep 30"),
            onDiagnosticOutput = output::append,
        )
        val owner = Thread.currentThread()
        val released = CountDownLatch(1)
        val watchdog = IndependentWatchdog(250) {
            process.expectOutputClosure()
            process.forceStop(2, TimeUnit.SECONDS)
            owner.interrupt()
            Log.e(TAG, "watchdog regression stoppedProcess=${!process.isAlive}")
        }
        var interrupted = false
        try {
            try {
                released.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
            assertTrue("Independent watchdog did not fire", watchdog.hasFired)
            assertTrue("Watchdog did not interrupt the blocked test thread", interrupted)
            assertTrue("Stalled child survived watchdog termination", !process.isAlive)
        } finally {
            watchdog.close()
            Thread.interrupted()
            if (process.isAlive) process.forceStop(2, TimeUnit.SECONDS)
            process.awaitOutputPumps(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun watchdogKillsGuestBeforePotentiallyBlockedDiagnosticCollection() {
        val process = NativeProcessSupervisor().start(
            command = listOf("/system/bin/sh", "-c", "exec sleep 30"),
        )
        val diagnosticsStarted = CountDownLatch(1)
        val releaseDiagnostics = CountDownLatch(1)
        val processStopped = CountDownLatch(1)
        val watchdog = IndependentWatchdog(250) {
            // The real timeout path must terminate first; a blocked snapshot or QMP probe
            // must not get a chance to keep the guest alive.
            process.expectOutputClosure()
            process.forceStop(2, TimeUnit.SECONDS)
            processStopped.countDown()
            diagnosticsStarted.countDown()
            releaseDiagnostics.await(10, TimeUnit.SECONDS)
        }
        try {
            assertTrue("Watchdog did not reach diagnostic collection", diagnosticsStarted.await(3, TimeUnit.SECONDS))
            assertTrue("Watchdog callback did not stop the guest before diagnostics", processStopped.count == 0L)
            assertFalse("Stalled guest survived while diagnostics were blocked", process.isAlive)
        } finally {
            releaseDiagnostics.countDown()
            watchdog.close()
            if (process.isAlive) process.forceStop(2, TimeUnit.SECONDS)
            process.awaitOutputPumps(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun slowWatchdogCallbackDoesNotBlockOtherDeadlineDispatch() {
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val fastFired = CountDownLatch(1)
        val slow = IndependentWatchdog(100) {
            slowStarted.countDown()
            releaseSlow.await(3, TimeUnit.SECONDS)
        }
        val fast = IndependentWatchdog(250) { fastFired.countDown() }
        try {
            assertTrue("First watchdog callback did not start", slowStarted.await(2, TimeUnit.SECONDS))
            assertTrue("A blocked callback delayed another deadline", fastFired.await(2, TimeUnit.SECONDS))
        } finally {
            releaseSlow.countDown()
            slow.close()
            fast.close()
        }
    }

    @Test
    fun runtimeTestJournalPersistsTimestampsAndRedactsSecretsAndPrivatePaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = "journal-${UUID.randomUUID()}"
        val journal = RuntimeTestJournal(context, runId)
        try {
            journal.record("stage", "password=not-for-logs path=${context.filesDir.absolutePath}/guest.img")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var contents = ""
            while (!contents.contains("guest.img") && System.nanoTime() < deadline) {
                contents = if (journal.file.isFile) journal.file.readText(Charsets.UTF_8) else ""
                if (!contents.contains("guest.img")) Thread.sleep(10)
            }
            assertTrue("Journal writer did not persist its queued stage", contents.contains("guest.img"))
            assertTrue("Journal has no timestamp", contents.contains(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
            assertTrue("Journal did not redact the password", contents.contains("password=[REDACTED]"))
            assertFalse("Journal exposed a private app path", contents.contains(context.filesDir.absolutePath))
            assertTrue("Journal did not persist the stage", contents.contains("<app-private>/guest.img"))
        } finally {
            journal.file.delete()
        }
    }

    @Test
    fun blockedRuntimeJournalStorageCannotBlockStageOrWatchdogCallers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writerStarted = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val journal = RuntimeTestJournal(context, "blocked-${UUID.randomUUID()}") { _, _ ->
            writerStarted.countDown()
            releaseWriter.await(10, TimeUnit.SECONDS)
        }
        try {
            journal.record("heartbeat", "stage=serial-wait")
            assertTrue("Journal writer did not reach the simulated storage stall", writerStarted.await(2, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            journal.record("watchdog-timeout", "stage=serial-wait killed=1/1")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("A blocked journal writer stalled the watchdog caller for ${elapsedMs}ms", elapsedMs < 250)
        } finally {
            releaseWriter.countDown()
            journal.file.delete()
        }
    }

    @Test(timeout = REAL_QEMU_STALL_TEST_CEILING_MS)
    fun packagedAlpineQemuIsStoppedWhenInstrumentationWaitIsBlocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeArm64(context)
        assumeTrue("At least 600 MiB free app storage is needed for the isolated guest disk", context.filesDir.usableSpace >= MIN_TEST_STORAGE_BYTES)
        val id = UUID.randomUUID().toString().replace("-", "")
        val journal = RuntimeTestJournal(context, "stall-$id")
        val installed = GuestAssetInstaller.forContext(context).install()
        val disk = freshValidationDisk(context, "stall-$id")
        val qmpSocket = File(context.filesDir, "qmp-stall-$id.sock")
        val console = SerialCapture("ephemeral-stall-secret", qmpSocket)
        val stderr = BoundedDiagnosticCapture()
        val process = AtomicReference<NativeProcessHandle?>()
        val aliveAtDeadline = AtomicReference<Boolean?>(null)
        val processStopped = CountDownLatch(1)
        val owner = Thread.currentThread()
        val watchdog = IndependentWatchdog(REAL_QEMU_STALL_TIMEOUT_MS) {
            val active = process.get()
            aliveAtDeadline.set(active?.isAlive)
            if (active != null) {
                runCatching { active.expectOutputClosure() }
                runCatching { active.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
            processStopped.countDown()
            owner.interrupt()
            val snapshot = active?.let { diagnosticSummary(it, console, stderr) } ?: "qemu=not-registered"
            journal.record("startup-watchdog", "fired=${active != null} $snapshot")
        }
        val heartbeat = IndependentHeartbeat(REAL_QEMU_STALL_HEARTBEAT_MS) {
            val active = process.get()
            journal.record(
                "startup-heartbeat",
                active?.let { diagnosticSummary(it, console, stderr) } ?: "qemu=not-registered",
            )
        }
        try {
            val launch = QemuLaunchConfig(
                executable = packagedQemu(context),
                kernel = installed.kernel,
                initramfs = installed.initramfs,
                rootDisk = disk,
                qmpSocket = qmpSocket,
                memoryMiB = 512,
                vcpus = 1,
                hostHttpPort = (40_000..59_999).random(),
            )
            journal.record("startup-launch", launch.diagnosticSummary())
            val handle = NativeProcessSupervisor().startSupervised(
                command = launch.toCommand(),
                workingDirectory = context.filesDir,
                onProcessStarted = { child ->
                    process.set(child)
                    if (watchdog.hasFired) {
                        child.expectOutputClosure()
                        child.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        processStopped.countDown()
                    }
                },
                onSerialOutput = console::receive,
                onDiagnosticOutput = stderr::append,
            )
            process.set(handle)
            val timedOut = try {
                processStopped.await(REAL_QEMU_STALL_TEST_CEILING_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                true
            }
            assertTrue("Real packaged QEMU did not hit its independent deadline", watchdog.hasFired)
            assertTrue("Real QEMU process was not stopped by the deadline", timedOut)
            assertEquals("Packaged QEMU exited before the watchdog exercised forced cleanup", true, aliveAtDeadline.get())
            assertFalse("Real packaged QEMU remained alive after watchdog cleanup", handle.isAlive)
            assertTrue("QEMU output pumps remained blocked after process termination", handle.awaitOutputPumps(3, TimeUnit.SECONDS))
        } finally {
            heartbeat.close()
            watchdog.close()
            Thread.interrupted()
            process.get()?.let { handle ->
                if (handle.isAlive) handle.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                handle.awaitOutputPumps(3, TimeUnit.SECONDS)
            }
            journal.record("startup-test-finished", "qemuAlive=${process.get()?.isAlive ?: false}")
            disk.delete()
            File(context.cacheDir, "pocketos-seed-stall-$id.gzip").delete()
            qmpSocket.delete()
        }
    }

    @Test
    fun supervisedStartRegistersChildBeforeOutputPumpsCanBlock() {
        var registered: NativeProcessHandle? = null
        var pumpsWereNotStartedAtRegistration = false
        val handle = NativeProcessSupervisor().startSupervised(
            command = listOf("/system/bin/sh", "-c", "exec sleep 30"),
            onProcessStarted = { process ->
                registered = process
                pumpsWereNotStartedAtRegistration = !process.awaitOutputPumps(1, TimeUnit.MILLISECONDS)
            },
        )
        try {
            assertTrue("Child was not registered before startup returned", registered === handle)
            assertTrue("Output pumps started before the watchdog could register the child", pumpsWereNotStartedAtRegistration)
        } finally {
            if (handle.isAlive) handle.forceStop(2, TimeUnit.SECONDS)
            handle.awaitOutputPumps(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun serialWriteTimeoutTerminatesAChildThatDoesNotReadInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val socket = File(context.cacheDir, "qmp-blocked-serial-${UUID.randomUUID()}.sock")
        val console = SerialCapture("ephemeral-serial-secret", socket)
        val stderr = BoundedDiagnosticCapture()
        val process = NativeProcessSupervisor().start(
            command = listOf("/system/bin/sh", "-c", "exec sleep 30"),
            onSerialOutput = console::receive,
            onDiagnosticOutput = stderr::append,
        )
        try {
            val failure = runCatching {
                writeSerialBounded(
                    "blocked serial writer regression",
                    process,
                    ByteArray(1024 * 1024),
                    console,
                    stderr,
                    timeoutMs = 250,
                )
            }.exceptionOrNull()
            assertNotNull("Blocked serial input was not bounded", failure)
            assertTrue(
                "Blocked serial input did not report its stage: $failure",
                failure!!.message.orEmpty().contains("serial write exceeded 250ms"),
            )
            assertFalse("Child survived bounded serial-write cleanup", process.isAlive)
            assertTrue("Output pumps survived bounded serial-write cleanup", process.awaitOutputPumps(3, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.forceStop(2, TimeUnit.SECONDS)
            runCatching { socket.delete() }
        }
    }

    @Test
    fun packagedArm64QemuRunsOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val qemu = packagedQemu(context)
        val output = StringBuffer()
        val versionSeen = CountDownLatch(1)
        val collect: (ByteArray) -> Unit = { bytes ->
            synchronized(output) {
                output.append(String(bytes, Charsets.UTF_8))
                if (output.contains("QEMU emulator version 11.1.2")) versionSeen.countDown()
            }
        }

        val handle = NativeProcessSupervisor().start(
            command = listOf(qemu.absolutePath, "--version"),
            onSerialOutput = collect,
            onDiagnosticOutput = collect,
        )
        try {
            assertTrue("QEMU did not exit within 30 seconds", handle.awaitExit(30, TimeUnit.SECONDS))
            assertEquals("QEMU --version exited unsuccessfully", 0, handle.exitCode)
            assertTrue("QEMU version output was not captured", versionSeen.await(5, TimeUnit.SECONDS))
        } finally {
            if (handle.isAlive) handle.forceStop()
        }

        synchronized(output) {
            assertTrue("Unexpected QEMU version output", output.contains("QEMU emulator version 11.1.2"))
        }
        AndroidRuntimeValidation.recordQemuExecutionVerified(context)
    }

    @Test(timeout = MAX_GUEST_TEST_DURATION_MS)
    fun bootsFreshAlpineSetsPasswordLogsInPersistsAndPowersOffGuest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cpuExecutionLease = CpuExecutionLease(context, "$TAG:physical-guest-test")
        cpuExecutionLease.acquire()
        val validationId = UUID.randomUUID().toString().replace("-", "")
        val activeProcesses = Collections.synchronizedList(mutableListOf<ActiveQemu>())
        val testThread = Thread.currentThread()
        val journal = RuntimeTestJournal(context, validationId)
        activeJournal = journal
        activeStage = "Initializing independent test watchdog"
        val watchdog = IndependentWatchdog(TEST_WATCHDOG_TIMEOUT_MS) {
            val active = synchronized(activeProcesses) { activeProcesses.toList() }
            var stopped = 0
            // Release a blocked wait immediately; the watchdog worker continues cleanup
            // independently even if the instrumentation thread starts unwinding.
            testThread.interrupt()
            // Terminate first. Diagnostics have their own worker and can never hold the
            // watchdog's kill path hostage if procfs or a QMP socket stops responding.
            active.forEach { guest ->
                runCatching { guest.process.expectOutputClosure() }
                if (runCatching { guest.process.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    stopped += 1
                }
            }
            val failedStage = activeStage
            Thread({
                val finalState = active.joinToString(" || ") { guest ->
                    runCatching { diagnosticSummary(guest.process, guest.console, guest.stderr) }
                        .getOrElse { "exitStatus=${guest.process.exitStatusLabel()} diagnosticsUnavailable=${it.javaClass.simpleName}" }
                }.ifEmpty { "no-qemu-process-registered" }
                journal.record("watchdog-timeout", "stage=$failedStage killed=$stopped/${active.size} $finalState")
                Log.e(TAG, "independent test deadline exceeded; stage=$failedStage killed=$stopped/${active.size} $finalState")
            }, "pocketos-timeout-diagnostics").apply {
                isDaemon = true
                start()
            }
            Log.e(TAG, "independent test deadline exceeded; stage=$failedStage killed=$stopped/${active.size}; test interrupted")
        }
        journal.record("test-start", "runId=$validationId timeoutMs=$TEST_WATCHDOG_TIMEOUT_MS")
        val heartbeatCount = AtomicLong(0)
        val diagnosticSnapshotRunning = AtomicBoolean(false)
        val heartbeat = IndependentHeartbeat(HEARTBEAT_INTERVAL_MS) {
            val active = synchronized(activeProcesses) { activeProcesses.toList() }
            if (active.isEmpty()) {
                journal.record("heartbeat", "stage=$activeStage qemu=not-yet-started")
            } else {
                val guest = active.lastOrNull { it.process.isAlive } ?: active.last()
                val pulse = heartbeatCount.incrementAndGet()
                val progress = "stage=$activeStage exitStatus=${guest.process.exitStatusLabel()} ${guest.console.progressSummary()}"
                // Emit this before any procfs or QMP work. A blocked diagnostic must not
                // suppress subsequent serial progress or the independent stage deadline.
                Log.i(TAG, "independent heartbeat $progress")
                journal.record("heartbeat-progress", progress)
                if (pulse % HEARTBEAT_TAIL_EVERY == 0L) {
                    val serialTail = guest.console.diagnosticTail().takeLast(HEARTBEAT_SERIAL_TAIL_CHARS)
                    journal.record("heartbeat-serial", "stage=$activeStage tail=$serialTail")
                    Log.i(TAG, "serial heartbeat stage=$activeStage tail=$serialTail")
                }
                if (pulse % HEARTBEAT_DIAGNOSTICS_EVERY == 0L && diagnosticSnapshotRunning.compareAndSet(false, true)) {
                    Thread({
                        try {
                            val snapshot = runCatching {
                                diagnosticSummary(guest.process, guest.console, guest.stderr, includeSerialTail = true)
                            }.getOrElse { "exitStatus=${guest.process.exitStatusLabel()} diagnosticsUnavailable=${it.javaClass.simpleName}" }
                            journal.record("heartbeat-diagnostics", "stage=$activeStage $snapshot")
                        } finally {
                            diagnosticSnapshotRunning.set(false)
                        }
                    }, "pocketos-heartbeat-diagnostics").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        }

        try {
            runFullGuestBoot(context, validationId, activeProcesses, watchdog)
        } finally {
            heartbeat.close()
            watchdog.close()
            journal.record("test-finished", "stage=$activeStage")
            activeJournal = null
            activeStage = "not-running"
            cpuExecutionLease.close()
        }
    }

    private fun runFullGuestBoot(
        context: Context,
        validationId: String,
        activeProcesses: MutableList<ActiveQemu>,
        watchdog: IndependentWatchdog,
    ) {
        reportStage("Checking ARM64 runtime and available app storage")
        assumeArm64(context)
        assumeTrue(
            "At least 600 MiB free app storage is needed for the seed and isolated test disk",
            context.filesDir.usableSpace >= MIN_TEST_STORAGE_BYTES,
        )

        reportStage("Installing and verifying packaged guest assets")
        val installed = GuestAssetInstaller.forContext(context).install()
        assertTrue("App-private persistent disk was not initialized as ext4", isExt4(installed.rootDisk))
        val disk = freshValidationDisk(context, validationId)
        val firstQmpSocket = File(context.filesDir, "qmp-v-$validationId-first.sock")
        val secondQmpSocket = File(context.filesDir, "qmp-v-$validationId-second.sock")
        val password = UUID.randomUUID().toString().replace("-", "").take(18)
        try {
            reportStage("Creating an isolated copy of the Alpine seed disk")
            val firstLease = GuestDiskLease.acquire(disk)
            var firstProcess: NativeProcessHandle? = null
            val firstConsole = SerialCapture(password, firstQmpSocket)
            val firstStderr = BoundedDiagnosticCapture()
            try {
                firstProcess = startGuest(context, installed, disk, firstQmpSocket, firstConsole, firstStderr, activeProcesses, watchdog)
                awaitStage("Waiting for the first-run password prompt", "New password:", firstConsole, firstProcess, firstStderr, GUEST_BOOT_TIMEOUT_MS)
                assertTrue("Guest readiness probe did not recognize first-run readiness", firstConsole.probe.isReady)
                reportStage("Submitting an ephemeral password to the isolated guest disk")
                writeSerialBounded("first-run password", firstProcess, "$password\n".toByteArray(Charsets.UTF_8), firstConsole, firstStderr)
                awaitStage("Waiting for password confirmation", "Retype password:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                writeSerialBounded("password confirmation", firstProcess, "$password\n".toByteArray(Charsets.UTF_8), firstConsole, firstStderr)
                awaitStage("Waiting for first-run setup completion", "POCKETOS_FIRST_BOOT_COMPLETE", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                awaitStage("Waiting for the non-root login prompt", "pocketos login:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)

                reportStage("Logging in as pocketos and checking account and disk marker")
                writeSerialBounded("first login", firstProcess, "pocketos\n".toByteArray(Charsets.UTF_8), firstConsole, firstStderr)
                awaitStage("Waiting for the account password prompt", "Password:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                writeSerialBounded("first account password", firstProcess, "$password\n".toByteArray(Charsets.UTF_8), firstConsole, firstStderr)
                awaitStage("Waiting for the account shell", "$ ", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(firstConsole, firstProcess, firstStderr, writePersistenceProbe = true)
                synchronizeGuestDisk(firstProcess, firstConsole, firstStderr)
                gracefulGuestPoweroff(firstProcess, firstConsole, firstStderr)
                synchronizeHostDisk(disk)
            } finally {
                stopIfStillRunning("first boot", firstProcess, firstConsole, firstStderr)
                runCatching { firstLease.close() }
                runCatching { firstQmpSocket.delete() }
            }

            reportStage("Rebooting the isolated disk to verify persisted credentials and marker")
            val secondLease = GuestDiskLease.acquire(disk)
            var secondProcess: NativeProcessHandle? = null
            val secondConsole = SerialCapture(password, secondQmpSocket)
            val secondStderr = BoundedDiagnosticCapture()
            try {
                reportStage("Launching a fresh QEMU process against the released persistent disk")
                secondProcess = startGuest(context, installed, disk, secondQmpSocket, secondConsole, secondStderr, activeProcesses, watchdog)
                awaitStage("Waiting for login after restart", "pocketos login:", secondConsole, secondProcess, secondStderr, GUEST_BOOT_TIMEOUT_MS, unexpectedToken = "New password:")
                assertFalse("First-run password setup repeated after restart", secondConsole.diagnosticTail().contains("New password:"))
                assertTrue("Readiness probe did not recognize login readiness", secondConsole.probe.isReady)

                writeSerialBounded("persisted login", secondProcess, "pocketos\n".toByteArray(Charsets.UTF_8), secondConsole, secondStderr)
                awaitStage("Waiting for the persisted account password prompt", "Password:", secondConsole, secondProcess, secondStderr, PROMPT_TIMEOUT_MS)
                writeSerialBounded("persisted account password", secondProcess, "$password\n".toByteArray(Charsets.UTF_8), secondConsole, secondStderr)
                awaitStage("Waiting for the persisted account shell", "$ ", secondConsole, secondProcess, secondStderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(secondConsole, secondProcess, secondStderr)
                synchronizeGuestDisk(secondProcess, secondConsole, secondStderr)
                gracefulGuestPoweroff(secondProcess, secondConsole, secondStderr)
            } finally {
                stopIfStillRunning("second boot", secondProcess, secondConsole, secondStderr)
                runCatching { secondLease.close() }
                runCatching { secondQmpSocket.delete() }
            }
        } finally {
            runCatching { disk.delete() }
            runCatching { File(context.cacheDir, "pocketos-seed-$validationId.gzip").delete() }
        }

        AndroidRuntimeValidation.recordGuestBootVerified(context)
    }

    private fun verifyGuestAccountAndPersistentMarker(
        console: SerialCapture,
        process: NativeProcessHandle,
        stderr: BoundedDiagnosticCapture,
        writePersistenceProbe: Boolean = false,
    ) {
        val persistenceProbe = if (writePersistenceProbe) {
            "printf 'pocketos-persistence-v1\\n' > \"\$HOME/.pocketos-persistence-probe\""
        } else {
            "test \"\$(cat \"\$HOME/.pocketos-persistence-probe\")\" = pocketos-persistence-v1"
        }
        val command =
            "test \"$(id -un)\" = pocketos && test \"$(id -u)\" != 0 && " +
                "test -f /var/lib/pocketos/user-setup-complete && $persistenceProbe && " +
                "echo __POCKETOS_ACCOUNT_AND_DISK_OK__\n"
        writeSerialBounded("account and persistence probe", process, command.toByteArray(Charsets.UTF_8), console, stderr)
        awaitStage("Checking non-root identity and persistent first-run marker", "__POCKETOS_ACCOUNT_AND_DISK_OK__", console, process, stderr, PROMPT_TIMEOUT_MS)
    }

    /** Flush writes, then shut Alpine down from the guest so ext4 unmounts before reboot. */
    private fun synchronizeGuestDisk(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ) {
        val marker = "__POCKETOS_GUEST_SYNC_COMPLETE__"
        writeSerialBounded("guest filesystem sync", process, "sync && echo $marker\n".toByteArray(Charsets.UTF_8), console, stderr)
        awaitStage("Waiting for guest filesystem sync", marker, console, process, stderr, PROMPT_TIMEOUT_MS)
    }

    /** Flush Android's file cache after QEMU exits, before releasing and reopening the same image. */
    private fun synchronizeHostDisk(disk: File) {
        val syncTask = FutureTask {
            java.io.RandomAccessFile(disk, "rw").use { image -> image.fd.sync() }
        }
        Thread(syncTask, "pocketos-host-disk-sync").apply {
            isDaemon = true
            start()
        }
        try {
            syncTask.get(HOST_DISK_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            syncTask.cancel(true)
            throw AssertionError("Android host disk flush exceeded its bounded deadline", failure)
        } catch (failure: Exception) {
            throw AssertionError("Android host disk flush failed", failure)
        }
        assertTrue("Persistent ext4 disk changed or became unreadable after shutdown", isExt4(disk))
        activeJournal?.record("host-disk-sync", "fsync=complete ext4=valid bytes=${disk.length()}")
    }

    /**
     * Android's Process output pipe can block if QEMU stops consuming serial stdin.
     * Keep test control writes off the instrumentation thread and terminate QEMU if
     * a write does not complete; otherwise a blocked write bypasses every stage timeout.
     */
    private fun writeSerialBounded(
        stage: String,
        process: NativeProcessHandle,
        bytes: ByteArray,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
        timeoutMs: Long = SERIAL_WRITE_TIMEOUT_MS,
    ) {
        activeJournal?.record("serial-write-start", "stage=$stage bytes=${bytes.size}")
        Log.i(TAG, "serial write start stage=$stage bytes=${bytes.size}")
        val write = FutureTask<Unit> { process.writeSerial(bytes) }
        Thread(write, "pocketos-serial-write").apply {
            isDaemon = true
            start()
        }
        try {
            write.get(timeoutMs, TimeUnit.MILLISECONDS)
            activeJournal?.record("serial-write-complete", "stage=$stage bytes=${bytes.size}")
            Log.i(TAG, "serial write complete stage=$stage bytes=${bytes.size}")
        } catch (failure: Exception) {
            write.cancel(true)
            val wasInterrupted = failure is InterruptedException
            if (wasInterrupted) Thread.interrupted()
            val snapshot = diagnosticSummary(process, console, stderr)
            process.expectOutputClosure()
            val stopped = runCatching {
                process.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            val pumpsStopped = runCatching {
                process.awaitOutputPumps(OUTPUT_PUMP_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            val cause = if (failure is TimeoutException) {
                "serial write exceeded ${timeoutMs}ms"
            } else {
                "serial write failed (${failure.javaClass.simpleName})"
            }
            val details = "$cause stage=$stage forcedStop=$stopped outputPumpsStopped=$pumpsStopped $snapshot"
            activeJournal?.record("serial-write-failure", details)
            Log.e(TAG, details)
            if (wasInterrupted) Thread.currentThread().interrupt()
            throw AssertionError(details, failure)
        }
    }

    private fun gracefulGuestPoweroff(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ) {
        reportStage("Requesting clean Alpine guest poweroff")
        writeSerialBounded("guest shutdown request", process, "pocketos-shutdown\n".toByteArray(Charsets.UTF_8), console, stderr)
        awaitStage("Waiting for shutdown command acknowledgement", "POCKETOS_SHUTDOWN_REQUESTED", console, process, stderr, SHUTDOWN_MARKER_TIMEOUT_MS)
        awaitStage("Waiting for root shutdown watcher acknowledgement", "POCKETOS_SHUTDOWN_WATCHER_ACCEPTED", console, process, stderr, SHUTDOWN_MARKER_TIMEOUT_MS)
        awaitStage("Waiting for guest disk sync confirmation", "POCKETOS_GUEST_SYNC_COMPLETE", console, process, stderr, GUEST_SYNC_TIMEOUT_MS)
        awaitStage(
            "Waiting for BusyBox init shutdown action completion",
            "POCKETOS_INIT_SHUTDOWN_ACTION_COMPLETE",
            console,
            process,
            stderr,
            GUEST_POWEROFF_TIMEOUT_SECONDS * 1_000,
        )
        reportStage("Waiting for QEMU exit after guest shutdown")
        if (!process.awaitExit(GUEST_POWEROFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val snapshot = diagnosticSummary(process, console, stderr)
            activeJournal?.record("guest-poweroff-timeout", snapshot)
            process.expectOutputClosure()
            val stopped = runCatching {
                process.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            val pumpsStopped = runCatching {
                process.awaitOutputPumps(OUTPUT_PUMP_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            throw AssertionError(
                "Alpine clean poweroff exceeded ${GUEST_POWEROFF_TIMEOUT_SECONDS}s; " +
                    "forcedStop=$stopped outputPumpsStopped=$pumpsStopped $snapshot",
            )
        }
        assertEquals("QEMU did not exit cleanly after guest poweroff", 0, process.exitCode)
        assertTrue(
            "QEMU output pumps remained active after guest poweroff",
            process.awaitOutputPumps(OUTPUT_PUMP_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        reportStage("Alpine guest powered off cleanly")
    }

    private fun startGuest(
        context: Context,
        assets: InstalledGuestAssets,
        disk: File,
        qmpSocket: File,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
        activeProcesses: MutableList<ActiveQemu>,
        watchdog: IndependentWatchdog,
    ): NativeProcessHandle {
        qmpSocket.delete()
        val launch = QemuLaunchConfig(
            executable = packagedQemu(context),
            kernel = assets.kernel,
            initramfs = assets.initramfs,
            rootDisk = disk,
            qmpSocket = qmpSocket,
            memoryMiB = 512,
            vcpus = 1,
            hostHttpPort = (40_000..59_999).random(),
        )
        activeJournal?.record("qemu-launch", launch.diagnosticSummary())
        Log.i(TAG, "qemu launch ${launch.diagnosticSummary()}")
        return NativeProcessSupervisor().startSupervised(
            command = launch.toCommand(),
            workingDirectory = context.filesDir,
            onProcessStarted = { process ->
                activeProcesses += ActiveQemu(process, console, stderr)
                if (watchdog.hasFired) {
                    runCatching { process.expectOutputClosure() }
                    val stopped = runCatching {
                        process.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }.getOrDefault(false)
                    val snapshot = runCatching { diagnosticSummary(process, console, stderr) }
                        .getOrElse { "exitStatus=${process.exitStatusLabel()} diagnosticsUnavailable=${it.javaClass.simpleName}" }
                    activeJournal?.record("late-qemu-registration", "stopped=$stopped $snapshot")
                    Log.e(TAG, "QEMU registered after the independent test deadline; stopped=$stopped $snapshot")
                }
            },
            onSerialOutput = console::receive,
            onDiagnosticOutput = stderr::append,
        )
    }

    private fun freshValidationDisk(context: Context, id: String): File {
        val expectedHash = context.assets.open("pocketos/assets.sha256").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                if (fields.size == 2 && fields[1] == "rootfs.ext4.gzip") fields[0] else null
            }.firstOrNull()
        } ?: error("Guest asset checksum manifest is missing the compressed root disk entry")
        val compressed = File(context.cacheDir, "pocketos-seed-$id.gzip")
        val disk = File(context.cacheDir, "pocketos-validation-$id.ext4")
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(context.assets.open("pocketos/rootfs.ext4.gzip")), digest).use { input ->
            BufferedOutputStream(FileOutputStream(compressed)).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("Compressed guest disk hash did not match assets.sha256", expectedHash, actualHash)
        GZIPInputStream(BufferedInputStream(FileInputStream(compressed))).use { input ->
            FileOutputStream(disk).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        assertTrue("Isolated test disk is not valid ext4", isExt4(disk))
        return disk
    }

    private fun isExt4(disk: File): Boolean {
        if (!disk.isFile || disk.length() < EXT4_MAGIC_OFFSET + 2) return false
        return disk.inputStream().use { input ->
            var remaining = EXT4_MAGIC_OFFSET
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) return@use false
                remaining -= skipped
            }
            input.read() == 0x53 && input.read() == 0xEF
        }
    }

    private fun packagedQemu(context: Context): File {
        val directory = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("ARM64 Android runtime required", directory.name == "arm64")
        return File(directory, "libqemu-system-aarch64.so").also { executable ->
            assertTrue("Packaged ARM64 QEMU is missing", executable.isFile)
            assertTrue("Packaged ARM64 QEMU is not executable", executable.canExecute())
        }
    }

    private fun assumeArm64(context: Context) {
        assumeTrue("ARM64 Android runtime required", File(context.applicationInfo.nativeLibraryDir).name == "arm64")
    }

    private class SerialCapture(private val password: String, val qmpSocket: File) {
        private val lock = Object()
        private val capture = BoundedDiagnosticCapture(MAX_DIAGNOSTIC_CHARS).apply { redact(password) }
        private val receivedBytes = AtomicLong(0)
        private val lastOutputNanos = AtomicLong(0)
        val probe = GuestReadinessProbe()

        fun receive(bytes: ByteArray) {
            probe.onSerialOutput(bytes)
            receivedBytes.addAndGet(bytes.size.toLong())
            lastOutputNanos.set(System.nanoTime())
            synchronized(lock) {
                capture.append(bytes)
                lock.notifyAll()
            }
        }

        fun await(
            token: String,
            process: NativeProcessHandle,
            timeoutMs: Long,
        ): Boolean = awaitAny(listOf(token), process, timeoutMs) == token

        fun awaitAny(
            tokens: List<String>,
            process: NativeProcessHandle,
            timeoutMs: Long,
        ): String? {
            require(tokens.isNotEmpty())
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                synchronized(lock) {
                    val snapshot = capture.snapshot()
                    tokens.firstOrNull(snapshot::contains)?.let { return it }
                    val now = System.nanoTime()
                    if (!process.isAlive || now >= deadline) return null
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - now).coerceAtLeast(1).coerceAtMost(250)
                    lock.wait(waitMs)
                }
            }
        }

        fun diagnosticTail(): String = capture.snapshot()

        fun progressSummary(): String {
            val lastOutput = lastOutputNanos.get()
            val ageMs = if (lastOutput == 0L) "never" else {
                TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - lastOutput).coerceAtLeast(0)).toString()
            }
            return "serialBytes=${receivedBytes.get()} lastSerialAgeMs=$ageMs readiness=${probe.isReady}"
        }
    }

    private data class ActiveQemu(
        val process: NativeProcessHandle,
        val console: SerialCapture,
        val stderr: BoundedDiagnosticCapture,
    )

    private fun awaitStage(
        stage: String,
        token: String,
        console: SerialCapture,
        process: NativeProcessHandle,
        stderr: BoundedDiagnosticCapture,
        timeoutMs: Long,
        unexpectedToken: String? = null,
    ) {
        val waiting = AtomicBoolean(true)
        val deadlineExpired = AtomicBoolean(false)
        val deadlineCallbackDone = CountDownLatch(1)
        val owner = Thread.currentThread()
        val deadline = IndependentWatchdog(timeoutMs) {
            if (waiting.compareAndSet(true, false)) {
                deadlineExpired.set(true)
                // Wake the supervised caller before any process wait or diagnostics can block.
                owner.interrupt()
                try {
                    val snapshot = runCatching { diagnosticSummary(process, console, stderr) }
                        .getOrElse { "exitStatus=${process.exitStatusLabel()} diagnosticsUnavailable(${it.javaClass.simpleName})" }
                    runCatching { process.expectOutputClosure() }
                    val stopped = runCatching {
                        process.forceStop(WATCHDOG_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }.getOrDefault(false)
                    activeJournal?.record("stage-deadline", "stage=$stage stopped=$stopped $snapshot")
                    Log.e(TAG, "stage deadline expired stage=$stage stopped=$stopped $snapshot")
                } finally {
                    deadlineCallbackDone.countDown()
                }
            }
        }
        val reached = try {
            reportStage(stage)
            // Keep the supervised wait limited to bounded serial checks and timed monitor
            // waits. Procfs/QMP diagnostics and storage writes never run on this thread.
            try {
                val observed = if (unexpectedToken == null) {
                    if (console.await(token, process, timeoutMs)) token else null
                } else {
                    console.awaitAny(listOf(token, unexpectedToken), process, timeoutMs)
                }
                val completedBeforeDeadline = waiting.compareAndSet(true, false)
                if (observed == unexpectedToken && unexpectedToken != null) {
                    throw AssertionError("Unexpected guest state while $stage: observed '$unexpectedToken'. ${diagnosticSummary(process, console, stderr)}")
                }
                observed == token && (completedBeforeDeadline || !deadlineExpired.get())
            } catch (interrupted: InterruptedException) {
                if (deadlineExpired.get()) false else throw interrupted
            }
        } finally {
            waiting.set(false)
            deadline.close()
            if (deadlineExpired.get()) {
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (deadlineCallbackDone.count > 0 && System.nanoTime() < cleanupDeadline) {
                    try {
                        deadlineCallbackDone.await(
                            (cleanupDeadline - System.nanoTime()).coerceAtLeast(1),
                            TimeUnit.NANOSECONDS,
                        )
                    } catch (_: InterruptedException) {
                        // Consume the deadline interrupt and wait for the callback to finish so
                        // the instrumentation runner cannot inherit it in the next test.
                    }
                }
                Thread.interrupted()
            }
        }
        if (!reached) {
            if (!process.isAlive) process.awaitOutputPumps(2, TimeUnit.SECONDS)
            val failure = if (deadlineExpired.get() || process.isAlive) "Timed out: $stage" else "Guest process exited before completing: $stage"
            throw AssertionError("$failure. ${diagnosticSummary(process, console, stderr)}")
        }
        activeJournal?.record("guest-milestone", "stage=$stage ${diagnosticSummary(process, console, stderr)}")
        reportStage("Reached: $stage")
    }

    private fun diagnosticSummary(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
        includeSerialTail: Boolean = true,
    ): String {
        val task = FutureTask { collectDiagnosticSummary(process, console, stderr, includeSerialTail) }
        Thread(task, "pocketos-diagnostic-snapshot").apply {
            isDaemon = true
            start()
        }
        return try {
            task.get(DIAGNOSTIC_SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            "exitStatus=${process.exitStatusLabel()}; diagnosticsUnavailable(snapshot-timeout)"
        } catch (failure: Exception) {
            "exitStatus=${process.exitStatusLabel()}; diagnosticsUnavailable(${failure.javaClass.simpleName})"
        }
    }

    private fun collectDiagnosticSummary(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
        includeSerialTail: Boolean,
    ): String {
        val processState = runCatching { process.diagnosticSnapshot() }
            .getOrElse { "qemuProcfs=unavailable(${it.javaClass.simpleName})" }
            .take(MAX_PROCESS_SNAPSHOT_CHARS)
        val qmpState = QmpClient().diagnosticProbe(console.qmpSocket.absolutePath)
        val tails = if (includeSerialTail) {
            val serialTail = console.diagnosticTail().takeLast(MAX_FAILURE_TAIL_CHARS)
            val stderrTail = stderr.snapshot().takeLast(MAX_FAILURE_TAIL_CHARS)
            "; serialTail=$serialTail; qemuStderrTail=$stderrTail"
        } else {
            "; ${console.progressSummary()}"
        }
        return "exitStatus=${process.exitStatusLabel()}; $processState; qmp=$qmpState$tails"
    }

    private fun stopIfStillRunning(
        stage: String,
        process: NativeProcessHandle?,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ) {
        if (process == null) return
        if (!process.isAlive) {
            runCatching { process.awaitOutputPumps(2, TimeUnit.SECONDS) }
            return
        }
        val wasInterrupted = Thread.interrupted()
        try {
            val snapshot = runCatching { diagnosticSummary(process, console, stderr) }
                .getOrElse { "diagnosticsUnavailable(${it.javaClass.simpleName}); exitStatus=${process.exitStatusLabel()}" }
            process.expectOutputClosure()
            val stopTask = FutureTask {
                process.forceStop(timeout = CLEANUP_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
            }
            Thread(stopTask, "pocketos-cleanup-$stage").apply {
                isDaemon = true
                start()
            }
            val stopped = try {
                stopTask.get(CLEANUP_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                stopTask.cancel(true)
                Log.e(TAG, "QEMU force-stop did not finish within its bound stage=$stage type=${failure.javaClass.simpleName}")
                false
            }
            val pumpsStopped = runCatching { process.awaitOutputPumps(2, TimeUnit.SECONDS) }.getOrDefault(false)
            runCatching { reportStage("Forcibly stopped QEMU after $stage did not shut down cleanly") }
            Log.e(TAG, "pre-force-stop stage=$stage $snapshot")
            Log.i(TAG, "QEMU cleanup stage=$stage stopped=$stopped exitStatus=${process.exitStatusLabel()} outputPumpsStopped=$pumpsStopped")
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun reportStage(stage: String) {
        activeStage = stage
        activeJournal?.record("stage", stage)
        Log.i(TAG, "stage=$stage")
        runCatching {
            stageStatusUpdates.execute {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("pocketos.stage", stage)
                })
            }
        }
    }

    companion object {
        private val stageStatusUpdates = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pocketos-stage-status").apply { isDaemon = true }
        }
        private const val TAG = "PocketOSRuntimeTest"
        private const val MAX_GUEST_TEST_DURATION_MS = 5 * 60 * 1_000L
        private const val TEST_WATCHDOG_TIMEOUT_MS = 4 * 60 * 1_000L
        private const val GUEST_BOOT_TIMEOUT_MS = 180_000L
        private const val PROMPT_TIMEOUT_MS = 30_000L
        private const val QMP_TIMEOUT_MS = 5_000
        private const val QMP_EXIT_TIMEOUT_MS = 15L
        private const val GUEST_POWEROFF_TIMEOUT_SECONDS = 20L
        private const val SERIAL_WRITE_TIMEOUT_MS = 3_000L
        private const val SHUTDOWN_MARKER_TIMEOUT_MS = 5_000L
        private const val GUEST_SYNC_TIMEOUT_MS = 15_000L
        private const val MIN_TEST_STORAGE_BYTES = 600L * 1024L * 1024L
        private const val EXT4_MAGIC_OFFSET = 1080L
        private const val MAX_DIAGNOSTIC_CHARS = 4_096
        private const val MAX_FAILURE_TAIL_CHARS = 2_048
        private const val MAX_PROCESS_SNAPSHOT_CHARS = 4_096
        private const val CLEANUP_TIMEOUT_SECONDS = 10L
        private const val OUTPUT_PUMP_EXIT_TIMEOUT_SECONDS = 5L
        private const val WATCHDOG_KILL_TIMEOUT_SECONDS = 2L
        private const val DIAGNOSTIC_SNAPSHOT_TIMEOUT_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TAIL_EVERY = 3L
        private const val HEARTBEAT_DIAGNOSTICS_EVERY = 3L
        private const val HEARTBEAT_SERIAL_TAIL_CHARS = 1_200
        private const val HOST_DISK_SYNC_TIMEOUT_MS = 15_000L
        private const val REAL_QEMU_STALL_TIMEOUT_MS = 5_000L
        private const val REAL_QEMU_STALL_HEARTBEAT_MS = 1_000L
        private const val REAL_QEMU_STALL_TEST_CEILING_MS = 20_000L
    }
}


package com.pocketos.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class QemuAndroidRuntimeInstrumentationTest {
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
        assertTrue("Failure did not include process exit state", diagnostic.contains("exitStatus=running"))
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
            Log.e(TAG, "watchdog regression pre-stop ${process.diagnosticSnapshot()}")
            process.forceStop(2, TimeUnit.SECONDS)
            owner.interrupt()
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
    fun bootsFreshAlpineSetsPasswordLogsInPersistsAndQuitsThroughQmp() {
        reportStage("Checking ARM64 runtime and available app storage")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeArm64(context)
        assumeTrue(
            "At least 600 MiB free app storage is needed for the seed and isolated test disk",
            context.filesDir.usableSpace >= MIN_TEST_STORAGE_BYTES,
        )

        reportStage("Installing and verifying packaged guest assets")
        val installed = GuestAssetInstaller.forContext(context).install()
        assertTrue("App-private persistent disk was not initialized as ext4", isExt4(installed.rootDisk))
        val validationId = UUID.randomUUID().toString().replace("-", "")
        val disk = freshValidationDisk(context, validationId)
        val qmpSocket = File(context.filesDir, "qmp-v-$validationId.sock")
        val password = UUID.randomUUID().toString().replace("-", "").take(18)
        val activeProcesses = Collections.synchronizedList(mutableListOf<ActiveQemu>())
        val testThread = Thread.currentThread()
        val watchdog = IndependentWatchdog(TEST_WATCHDOG_TIMEOUT_MS) {
            val active = synchronized(activeProcesses) { activeProcesses.toList() }
            active.forEach { guest ->
                val process = guest.process
                val snapshot = runCatching { diagnosticSummary(process, guest.console, guest.stderr) }
                    .getOrElse { "exitStatus=${process.exitStatusLabel()} diagnosticsUnavailable=${it.javaClass.simpleName}" }
                Log.e(TAG, "independent test deadline exceeded; pre-stop $snapshot")
                runCatching { process.forceStop(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }
            Log.e(TAG, "independent test deadline exceeded; terminating blocked instrumentation wait")
            testThread.interrupt()
        }

        try {
            reportStage("Creating an isolated copy of the Alpine seed disk")
            val firstLease = GuestDiskLease.acquire(disk)
            var firstProcess: NativeProcessHandle? = null
            val firstConsole = SerialCapture(password, qmpSocket)
            val firstStderr = BoundedDiagnosticCapture()
            try {
                firstProcess = startGuest(context, installed, disk, qmpSocket, firstConsole, firstStderr, activeProcesses)
                awaitStage("Waiting for the first-run password prompt", "New password:", firstConsole, firstProcess, firstStderr, GUEST_BOOT_TIMEOUT_MS)
                assertTrue("Guest readiness probe did not recognize first-run readiness", firstConsole.probe.isReady)
                reportStage("Submitting an ephemeral password to the isolated guest disk")
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for password confirmation", "Retype password:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for first-run setup completion", "POCKETOS_FIRST_BOOT_COMPLETE", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                awaitStage("Waiting for the non-root login prompt", "pocketos login:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)

                reportStage("Logging in as pocketos and checking account and disk marker")
                firstProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the account password prompt", "Password:", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the account shell", "$ ", firstConsole, firstProcess, firstStderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(firstConsole, firstProcess, firstStderr)
                gracefulQmpShutdown(firstProcess, qmpSocket, firstConsole, firstStderr)
            } finally {
                stopIfStillRunning("first boot", firstProcess, firstConsole, firstStderr)
                runCatching { firstLease.close() }
                runCatching { qmpSocket.delete() }
            }

            reportStage("Rebooting the isolated disk to verify persisted credentials and marker")
            val secondLease = GuestDiskLease.acquire(disk)
            var secondProcess: NativeProcessHandle? = null
            val secondConsole = SerialCapture(password, qmpSocket)
            val secondStderr = BoundedDiagnosticCapture()
            try {
                secondProcess = startGuest(context, installed, disk, qmpSocket, secondConsole, secondStderr, activeProcesses)
                awaitStage("Waiting for login after restart", "pocketos login:", secondConsole, secondProcess, secondStderr, GUEST_BOOT_TIMEOUT_MS)
                assertTrue("Readiness probe did not recognize login readiness", secondConsole.probe.isReady)

                secondProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the persisted account password prompt", "Password:", secondConsole, secondProcess, secondStderr, PROMPT_TIMEOUT_MS)
                secondProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the persisted account shell", "$ ", secondConsole, secondProcess, secondStderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(secondConsole, secondProcess, secondStderr)
                gracefulQmpShutdown(secondProcess, qmpSocket, secondConsole, secondStderr)
            } finally {
                stopIfStillRunning("second boot", secondProcess, secondConsole, secondStderr)
                runCatching { secondLease.close() }
                runCatching { qmpSocket.delete() }
            }
        } finally {
            watchdog.close()
            runCatching { disk.delete() }
            runCatching { File(context.cacheDir, "pocketos-seed-$validationId.gzip").delete() }
        }

        AndroidRuntimeValidation.recordGuestBootVerified(context)
    }

    private fun verifyGuestAccountAndPersistentMarker(
        console: SerialCapture,
        process: NativeProcessHandle,
        stderr: BoundedDiagnosticCapture,
    ) {
        val command =
            "test \"$(id -un)\" = pocketos && test \"$(id -u)\" != 0 && " +
                "test -f /var/lib/pocketos/user-setup-complete && echo __POCKETOS_ACCOUNT_AND_DISK_OK__\n"
        process.writeSerial(command.toByteArray(Charsets.UTF_8))
        awaitStage("Checking non-root identity and persistent first-run marker", "__POCKETOS_ACCOUNT_AND_DISK_OK__", console, process, stderr, PROMPT_TIMEOUT_MS)
    }

    private fun gracefulQmpShutdown(
        process: NativeProcessHandle,
        socket: File,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ) {
        reportStage("Requesting graceful QMP shutdown")
        try {
            QmpClient().requestQuit(socket.absolutePath, timeoutMillis = QMP_TIMEOUT_MS)
        } catch (failure: Exception) {
            throw AssertionError("QMP shutdown request failed. ${diagnosticSummary(process, console, stderr)}", failure)
        }
        assertTrue("QEMU did not exit after QMP quit. ${diagnosticSummary(process, console, stderr)}", process.awaitExit(QMP_EXIT_TIMEOUT_MS, TimeUnit.SECONDS))
        assertEquals("QEMU did not exit cleanly after QMP quit", 0, process.exitCode)
        reportStage("QEMU exited cleanly through QMP")
    }

    private fun startGuest(
        context: Context,
        assets: InstalledGuestAssets,
        disk: File,
        qmpSocket: File,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
        activeProcesses: MutableList<ActiveQemu>,
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
        Log.i(TAG, "qemu launch ${launch.diagnosticSummary()}")
        return NativeProcessSupervisor().startSupervised(
            command = launch.toCommand(),
            workingDirectory = context.filesDir,
            onProcessStarted = { process -> activeProcesses += ActiveQemu(process, console, stderr) },
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
        val probe = GuestReadinessProbe()

        fun receive(bytes: ByteArray) {
            probe.onSerialOutput(bytes)
            synchronized(lock) {
                capture.append(bytes)
                lock.notifyAll()
            }
        }

        fun await(
            token: String,
            process: NativeProcessHandle,
            timeoutMs: Long,
            onHeartbeat: () -> Unit,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var nextHeartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MS)
            while (true) {
                var emitHeartbeat = false
                synchronized(lock) {
                    if (capture.snapshot().contains(token)) return true
                    val now = System.nanoTime()
                    if (!process.isAlive || now >= deadline) return false
                    val remaining = minOf(deadline - now, nextHeartbeat - now)
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(250)
                    lock.wait(waitMs)
                    if (capture.snapshot().contains(token)) return true
                    emitHeartbeat = System.nanoTime() >= nextHeartbeat
                }
                if (emitHeartbeat) {
                    // Keep procfs reads and logcat I/O outside the serial pump's monitor.
                    onHeartbeat()
                    nextHeartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MS)
                }
            }
        }

        fun diagnosticTail(): String = capture.snapshot()
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
    ) {
        reportStage(stage)
        val startedAt = System.nanoTime()
        val reached = console.await(token, process, timeoutMs) {
            val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
            Log.i(
                TAG,
                "wait heartbeat stage=$stage elapsedSeconds=$elapsedSeconds " +
                    "serialChars=${console.diagnosticTail().length} stderrChars=${stderr.snapshot().length}; " +
                    process.diagnosticSnapshot(),
            )
        }
        if (!reached) {
            if (!process.isAlive) process.awaitOutputPumps(2, TimeUnit.SECONDS)
            val failure = if (process.isAlive) "Timed out: $stage" else "Guest process exited before completing: $stage"
            throw AssertionError("$failure. ${diagnosticSummary(process, console, stderr)}")
        }
        reportStage("Reached: $stage")
    }

    private fun diagnosticSummary(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ): String {
        val processState = runCatching { process.diagnosticSnapshot() }
            .getOrElse { "qemuProcfs=unavailable(${it.javaClass.simpleName})" }
            .take(MAX_PROCESS_SNAPSHOT_CHARS)
        val qmpState = QmpClient().diagnosticProbe(console.qmpSocket.absolutePath)
        val serialTail = console.diagnosticTail().takeLast(MAX_FAILURE_TAIL_CHARS)
        val stderrTail = stderr.snapshot().takeLast(MAX_FAILURE_TAIL_CHARS)
        return "exitStatus=${process.exitStatusLabel()}; $processState; qmp=$qmpState; " +
            "serialTail=$serialTail; qemuStderrTail=$stderrTail"
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
            runCatching { reportStage("Forcibly stopping QEMU after $stage did not shut down cleanly") }
            Log.e(TAG, "pre-force-stop stage=$stage $snapshot")
            val stopped = runCatching {
                process.forceStop(timeout = CLEANUP_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
            }.getOrElse {
                Log.e(TAG, "QEMU force-stop failed stage=$stage type=${it.javaClass.simpleName}")
                false
            }
            val pumpsStopped = runCatching { process.awaitOutputPumps(2, TimeUnit.SECONDS) }.getOrDefault(false)
            Log.i(TAG, "QEMU cleanup stage=$stage stopped=$stopped exitStatus=${process.exitStatusLabel()} outputPumpsStopped=$pumpsStopped")
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun reportStage(stage: String) {
        Log.i(TAG, "stage=$stage")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("pocketos.stage", stage)
        })
    }

    companion object {
        private const val TAG = "PocketOSRuntimeTest"
        private const val MAX_GUEST_TEST_DURATION_MS = 12 * 60 * 1_000L
        private const val TEST_WATCHDOG_TIMEOUT_MS = 8 * 60 * 1_000L
        private const val GUEST_BOOT_TIMEOUT_MS = 180_000L
        private const val PROMPT_TIMEOUT_MS = 30_000L
        private const val QMP_TIMEOUT_MS = 5_000
        private const val QMP_EXIT_TIMEOUT_MS = 15L
        private const val MIN_TEST_STORAGE_BYTES = 600L * 1024L * 1024L
        private const val EXT4_MAGIC_OFFSET = 1080L
        private const val MAX_DIAGNOSTIC_CHARS = 4_096
        private const val MAX_FAILURE_TAIL_CHARS = 2_048
        private const val MAX_PROCESS_SNAPSHOT_CHARS = 4_096
        private const val CLEANUP_TIMEOUT_SECONDS = 10L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

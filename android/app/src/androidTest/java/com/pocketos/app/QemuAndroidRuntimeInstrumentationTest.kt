package com.pocketos.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class QemuAndroidRuntimeInstrumentationTest {
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

        try {
            reportStage("Creating an isolated copy of the Alpine seed disk")
            val firstLease = GuestDiskLease.acquire(disk)
            var firstProcess: NativeProcessHandle? = null
            try {
                val console = SerialCapture(password)
                val stderr = BoundedDiagnosticCapture()
                firstProcess = startGuest(context, installed, disk, qmpSocket, console, stderr)
                awaitStage("Waiting for the first-run password prompt", "New password:", console, firstProcess, stderr, GUEST_BOOT_TIMEOUT_MS)
                assertTrue("Guest readiness probe did not recognize first-run readiness", console.probe.isReady)
                reportStage("Submitting an ephemeral password to the isolated guest disk")
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for password confirmation", "Retype password:", console, firstProcess, stderr, PROMPT_TIMEOUT_MS)
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for first-run setup completion", "POCKETOS_FIRST_BOOT_COMPLETE", console, firstProcess, stderr, PROMPT_TIMEOUT_MS)
                awaitStage("Waiting for the non-root login prompt", "pocketos login:", console, firstProcess, stderr, PROMPT_TIMEOUT_MS)

                reportStage("Logging in as pocketos and checking account and disk marker")
                firstProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the account password prompt", "Password:", console, firstProcess, stderr, PROMPT_TIMEOUT_MS)
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the account shell", "$ ", console, firstProcess, stderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(console, firstProcess, stderr)
                gracefulQmpShutdown(firstProcess, qmpSocket, console, stderr)
            } finally {
                stopIfStillRunning("first boot", firstProcess)
                firstLease.close()
                qmpSocket.delete()
            }

            reportStage("Rebooting the isolated disk to verify persisted credentials and marker")
            val secondLease = GuestDiskLease.acquire(disk)
            var secondProcess: NativeProcessHandle? = null
            try {
                val console = SerialCapture(password)
                val stderr = BoundedDiagnosticCapture()
                secondProcess = startGuest(context, installed, disk, qmpSocket, console, stderr)
                awaitStage("Waiting for login after restart", "pocketos login:", console, secondProcess, stderr, GUEST_BOOT_TIMEOUT_MS)
                assertTrue("Readiness probe did not recognize login readiness", console.probe.isReady)

                secondProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the persisted account password prompt", "Password:", console, secondProcess, stderr, PROMPT_TIMEOUT_MS)
                secondProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                awaitStage("Waiting for the persisted account shell", "$ ", console, secondProcess, stderr, PROMPT_TIMEOUT_MS)
                verifyGuestAccountAndPersistentMarker(console, secondProcess, stderr)
                gracefulQmpShutdown(secondProcess, qmpSocket, console, stderr)
            } finally {
                stopIfStillRunning("second boot", secondProcess)
                secondLease.close()
                qmpSocket.delete()
            }
        } finally {
            disk.delete()
            File(context.cacheDir, "pocketos-seed-$validationId.gzip").delete()
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
        return NativeProcessSupervisor().start(
            command = launch.toCommand(),
            workingDirectory = context.filesDir,
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

    private class SerialCapture(private val password: String) {
        private val lock = Object()
        private val capture = BoundedDiagnosticCapture(MAX_SERIAL_CHARS).apply { redact(password) }
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
            throw AssertionError("Timed out: $stage. ${diagnosticSummary(process, console, stderr)}")
        }
        reportStage("Reached: $stage")
    }

    private fun diagnosticSummary(
        process: NativeProcessHandle,
        console: SerialCapture,
        stderr: BoundedDiagnosticCapture,
    ): String = "${process.diagnosticSnapshot()}; serialTail=${console.diagnosticTail()}; qemuStderrTail=${stderr.snapshot()}"

    private fun stopIfStillRunning(stage: String, process: NativeProcessHandle?) {
        if (process?.isAlive != true) return
        val wasInterrupted = Thread.interrupted()
        try {
            reportStage("Forcibly stopping QEMU after $stage did not shut down cleanly")
            val stopped = process.forceStop(timeout = CLEANUP_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
            if (!stopped) Log.e(TAG, "QEMU remained alive after bounded cleanup stage=$stage")
        } catch (failure: Exception) {
            Log.e(TAG, "QEMU cleanup encountered an error stage=$stage", failure)
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
        private const val GUEST_BOOT_TIMEOUT_MS = 180_000L
        private const val PROMPT_TIMEOUT_MS = 30_000L
        private const val QMP_TIMEOUT_MS = 5_000
        private const val QMP_EXIT_TIMEOUT_MS = 15L
        private const val MIN_TEST_STORAGE_BYTES = 600L * 1024L * 1024L
        private const val EXT4_MAGIC_OFFSET = 1080L
        private const val MAX_SERIAL_CHARS = 8_192
        private const val CLEANUP_TIMEOUT_SECONDS = 10L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

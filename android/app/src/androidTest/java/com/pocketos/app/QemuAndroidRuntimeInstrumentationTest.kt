package com.pocketos.app

import android.content.Context
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

    @Test
    fun bootsFreshAlpineSetsPasswordLogsInPersistsAndQuitsThroughQmp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeArm64(context)
        assumeTrue(
            "At least 600 MiB free app storage is needed for the seed and isolated test disk",
            context.filesDir.usableSpace >= MIN_TEST_STORAGE_BYTES,
        )

        val installed = GuestAssetInstaller.forContext(context).install()
        assertTrue("App-private persistent disk was not initialized as ext4", isExt4(installed.rootDisk))
        val validationId = UUID.randomUUID().toString().replace("-", "")
        val disk = freshValidationDisk(context, validationId)
        val qmpSocket = File(context.filesDir, "qmp-v-$validationId.sock")
        val password = UUID.randomUUID().toString().replace("-", "").take(18)

        try {
            val firstLease = GuestDiskLease.acquire(disk)
            var firstProcess: NativeProcessHandle? = null
            try {
                val console = SerialCapture()
                firstProcess = startGuest(context, installed, disk, qmpSocket, console)
                assertTrue(
                    "Guest did not reach the first-run password prompt",
                    console.await("New password:", firstProcess, GUEST_BOOT_TIMEOUT_MS),
                )
                assertTrue("Guest readiness probe did not recognize first-run readiness", console.probe.isReady)
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                assertTrue(
                    "Guest did not reach password confirmation",
                    console.await("Retype password:", firstProcess, PROMPT_TIMEOUT_MS),
                )
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                assertTrue(
                    "First-run setup did not finish",
                    console.await("POCKETOS_FIRST_BOOT_COMPLETE", firstProcess, PROMPT_TIMEOUT_MS),
                )
                assertTrue(
                    "Non-root login prompt did not appear",
                    console.await("pocketos login:", firstProcess, PROMPT_TIMEOUT_MS),
                )

                firstProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                assertTrue(
                    "Guest did not request the new account password",
                    console.await("Password:", firstProcess, PROMPT_TIMEOUT_MS),
                )
                firstProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                assertTrue("Guest did not enter the account shell", console.await("$ ", firstProcess, PROMPT_TIMEOUT_MS))
                verifyGuestAccountAndPersistentMarker(console, firstProcess)
                gracefulQmpShutdown(firstProcess, qmpSocket)
            } finally {
                if (firstProcess?.isAlive == true) firstProcess.forceStop()
                firstLease.close()
                qmpSocket.delete()
            }

            val secondLease = GuestDiskLease.acquire(disk)
            var secondProcess: NativeProcessHandle? = null
            try {
                val console = SerialCapture()
                secondProcess = startGuest(context, installed, disk, qmpSocket, console)
                assertTrue(
                    "Guest did not return to login after restart",
                    console.await("pocketos login:", secondProcess, GUEST_BOOT_TIMEOUT_MS),
                )
                assertTrue("Readiness probe did not recognize login readiness", console.probe.isReady)

                secondProcess.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
                assertTrue(
                    "Guest did not request the persisted account password",
                    console.await("Password:", secondProcess, PROMPT_TIMEOUT_MS),
                )
                secondProcess.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
                assertTrue(
                    "Guest did not enter the persisted account shell",
                    console.await("$ ", secondProcess, PROMPT_TIMEOUT_MS),
                )
                verifyGuestAccountAndPersistentMarker(console, secondProcess)
                gracefulQmpShutdown(secondProcess, qmpSocket)
            } finally {
                if (secondProcess?.isAlive == true) secondProcess.forceStop()
                secondLease.close()
                qmpSocket.delete()
            }
        } finally {
            disk.delete()
            File(context.cacheDir, "pocketos-seed-$validationId.gzip").delete()
        }

        AndroidRuntimeValidation.recordGuestBootVerified(context)
    }

    private fun verifyGuestAccountAndPersistentMarker(console: SerialCapture, process: NativeProcessHandle) {
        val command =
            "test \"$(id -un)\" = pocketos && test \"$(id -u)\" != 0 && " +
                "test -f /var/lib/pocketos/user-setup-complete && echo __POCKETOS_ACCOUNT_AND_DISK_OK__\n"
        process.writeSerial(command.toByteArray(Charsets.UTF_8))
        assertTrue(
            "Guest login, non-root account, or persistent first-run marker failed",
            console.await("__POCKETOS_ACCOUNT_AND_DISK_OK__", process, PROMPT_TIMEOUT_MS),
        )
    }

    private fun gracefulQmpShutdown(process: NativeProcessHandle, socket: File) {
        QmpClient().requestQuit(socket.absolutePath, timeoutMillis = QMP_TIMEOUT_MS)
        assertTrue("QEMU did not exit after QMP quit", process.awaitExit(QMP_EXIT_TIMEOUT_MS, TimeUnit.SECONDS))
        assertEquals("QEMU did not exit cleanly after QMP quit", 0, process.exitCode)
    }

    private fun startGuest(
        context: Context,
        assets: InstalledGuestAssets,
        disk: File,
        qmpSocket: File,
        console: SerialCapture,
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

    private class SerialCapture {
        private val lock = Object()
        private val text = StringBuilder()
        val probe = GuestReadinessProbe()

        fun receive(bytes: ByteArray) {
            probe.onSerialOutput(bytes)
            synchronized(lock) {
                text.append(String(bytes, Charsets.UTF_8))
                if (text.length > MAX_SERIAL_CHARS) text.delete(0, text.length - MAX_SERIAL_CHARS)
                lock.notifyAll()
            }
        }

        fun await(token: String, process: NativeProcessHandle, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            synchronized(lock) {
                while (!text.contains(token)) {
                    if (!process.isAlive || System.nanoTime() >= deadline) return false
                    val remaining = deadline - System.nanoTime()
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(250)
                    lock.wait(waitMs)
                }
                return true
            }
        }
    }

    companion object {
        private const val GUEST_BOOT_TIMEOUT_MS = 180_000L
        private const val PROMPT_TIMEOUT_MS = 30_000L
        private const val QMP_TIMEOUT_MS = 5_000
        private const val QMP_EXIT_TIMEOUT_MS = 15L
        private const val MIN_TEST_STORAGE_BYTES = 600L * 1024L * 1024L
        private const val EXT4_MAGIC_OFFSET = 1080L
        private const val MAX_SERIAL_CHARS = 65_536
    }
}

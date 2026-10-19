package com.pocketos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QemuAndroidRuntimeInstrumentationTest {
    @Test
    fun packagedArm64QemuRunsOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraryDirectory = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("ARM64 Android runtime required", libraryDirectory.name == "arm64")
        val qemu = File(libraryDirectory, "libqemu-system-aarch64.so")
        assertTrue("Packaged ARM64 QEMU is missing: $qemu", qemu.isFile)
        assertTrue("Packaged ARM64 QEMU is not executable: $qemu", qemu.canExecute())

        val output = StringBuffer()
        val versionSeen = java.util.concurrent.CountDownLatch(1)
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
    fun packagedAlpineGuestCompletesFirstRunLoginPersistenceAndQmpShutdown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraryDirectory = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("ARM64 Android runtime required", libraryDirectory.name == "arm64")
        val qemu = File(libraryDirectory, "libqemu-system-aarch64.so")
        assertTrue("Packaged ARM64 QEMU is missing", qemu.isFile && qemu.canExecute())
        assumeTrue("At least 600 MiB free app storage is required for isolated guest boot", context.cacheDir.usableSpace >= 600L * 1024L * 1024L)

        // Initialize and validate PocketOS's persistent ext4 seed, but never boot or modify it.
        val persistentAssets = GuestAssetInstaller.forContext(context).install()
        assertTrue("Persistent guest storage is not ext4", isExt4(persistentAssets.rootDisk))

        // Build a fresh isolated seed in cache for password setup and reboot checks.
        val isolatedDirectory = File(context.cacheDir, "runtime-validation")
        isolatedDirectory.deleteRecursively()
        val isolatedAssets = GuestAssetInstaller(
            assetSource = GuestAssetSource { name -> context.assets.open("pocketos/$name") },
            filesDirectory = isolatedDirectory,
        ).install()
        assertTrue("Isolated guest test disk is not ext4", isExt4(isolatedAssets.rootDisk))

        val password = "P" + UUID.randomUUID().toString().replace("-", "")
        val qmpSocket = File(context.cacheDir, "pocketos-validation.qmp")
        qmpSocket.delete()
        val lease = GuestDiskLease.acquire(isolatedAssets.rootDisk)
        try {
            val first = launchAndProvision(qemu, isolatedAssets, qmpSocket, password)
            gracefulQuit(first, qmpSocket)

            val second = launchAndLogin(qemu, isolatedAssets, qmpSocket, password)
            val marker = "POCKETOS_PERSISTED_LOGIN_" + UUID.randomUUID().toString().replace("-", "")
            second.writeSerial(
                ("test \"$(id -u)\" != 0 && test -f /var/lib/pocketos/user-setup-complete && echo $marker\n")
                    .toByteArray(Charsets.UTF_8),
            )
            waitForSerial(second, marker, 30)
            gracefulQuit(second, qmpSocket)
        } finally {
            lease.close()
            qmpSocket.delete()
            isolatedDirectory.deleteRecursively()
        }
        AndroidRuntimeValidation.recordGuestBootVerified(context)
    }

    private fun launchAndProvision(qemu: File, assets: InstalledGuestAssets, qmpSocket: File, password: String): NativeProcessHandle {
        val process = launch(qemu, assets, qmpSocket)
        try {
            waitForSerial(process, "New password:", 180)
            process.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
            waitForSerial(process, "Retype password:", 30)
            process.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
            waitForSerial(process, "POCKETOS_FIRST_BOOT_COMPLETE", 120)
            waitForSerial(process, "pocketos login:", 30)
            return process
        } catch (failure: Throwable) {
            stopAfterFailure(process, qmpSocket)
            throw failure
        }
    }

    private fun launchAndLogin(qemu: File, assets: InstalledGuestAssets, qmpSocket: File, password: String): NativeProcessHandle {
        qmpSocket.delete()
        val process = launch(qemu, assets, qmpSocket)
        try {
            waitForSerial(process, "pocketos login:", 180)
            process.writeSerial("pocketos\n".toByteArray(Charsets.UTF_8))
            waitForSerial(process, "Password:", 30)
            process.writeSerial("$password\n".toByteArray(Charsets.UTF_8))
            waitForSerial(process, "$ ", 30)
            return process
        } catch (failure: Throwable) {
            stopAfterFailure(process, qmpSocket)
            throw failure
        }
    }

    private fun launch(qemu: File, assets: InstalledGuestAssets, qmpSocket: File): NativeProcessHandle =
        NativeProcessSupervisor().start(
            command = QemuLaunchConfig(
                executable = qemu,
                kernel = assets.kernel,
                initramfs = assets.initramfs,
                rootDisk = assets.rootDisk,
                qmpSocket = qmpSocket,
                memoryMiB = 512,
                vcpus = 1,
            ).toCommand(),
            workingDirectory = assets.rootDisk.parentFile,
            onSerialOutput = { bytes -> serial.append(bytes) },
        )

    private fun gracefulQuit(process: NativeProcessHandle, qmpSocket: File) {
        try {
            waitUntil(qmpSocket, timeoutSeconds = 20)
            QmpClient().requestQuit(qmpSocket.absolutePath, timeoutMillis = 5_000)
            assertTrue("QEMU did not exit after QMP quit", process.awaitExit(20, TimeUnit.SECONDS))
            assertEquals("QEMU did not exit cleanly after QMP quit", 0, process.exitCode)
        } finally {
            if (process.isAlive) process.forceStop()
            qmpSocket.delete()
            serial.clear()
        }
    }

    private fun stopAfterFailure(process: NativeProcessHandle, qmpSocket: File) {
        if (process.isAlive) {
            runCatching { QmpClient().requestQuit(qmpSocket.absolutePath, timeoutMillis = 2_000) }
            if (!process.awaitExit(5, TimeUnit.SECONDS)) process.forceStop()
        }
        qmpSocket.delete()
        serial.clear()
    }

    private fun waitForSerial(process: NativeProcessHandle, token: String, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (serial.contains(token)) return
            if (!process.isAlive) {
                throw AssertionError("QEMU exited before guest reached an expected serial stage (exit " + process.exitCode + ")")
            }
            Thread.sleep(100)
        }
        throw AssertionError("Guest did not reach an expected serial stage within $timeoutSeconds seconds")
    }

    private fun waitUntil(file: File, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (file.exists()) return
            Thread.sleep(50)
        }
        throw AssertionError("QMP socket did not become available")
    }

    private fun isExt4(file: File): Boolean {
        if (!file.isFile || file.length() < 1082) return false
        return java.io.RandomAccessFile(file, "r").use {
            var skipped = 0L
            while (skipped < 1080L) {
                val count = it.skipBytes((1080L - skipped).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (count <= 0) return@use false
                skipped += count
            }
            it.readUnsignedByte() == 0x53 && it.readUnsignedByte() == 0xEF
        }
    }

    private object serial {
        private const val MAX_CHARS = 64 * 1024
        private val output = StringBuilder()
        @Synchronized fun append(bytes: ByteArray) {
            output.append(String(bytes, Charsets.UTF_8))
            if (output.length > MAX_CHARS) output.delete(0, output.length - MAX_CHARS)
        }
        @Synchronized fun contains(token: String): Boolean = output.contains(token)
        @Synchronized fun clear() = output.setLength(0)
    }
}

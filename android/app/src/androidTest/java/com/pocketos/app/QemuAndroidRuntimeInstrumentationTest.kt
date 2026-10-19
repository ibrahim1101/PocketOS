package com.pocketos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
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
        val versionSeen = CountDownLatch(1)
        val collect: (ByteArray) -> Unit = { bytes ->
            synchronized(output) {
                output.append(String(bytes, Charsets.UTF_8))
                if (output.contains("QEMU emulator version 11.1.2")) {
                    versionSeen.countDown()
                }
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
            assertTrue("Unexpected QEMU version output: $output", output.contains("QEMU emulator version 11.1.2"))
        }
        AndroidRuntimeValidation.recordQemuExecutionVerified(context)
    }

    @Test
    fun packagedAlpineGuestBootsToFirstRunOrLoginPrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraryDirectory = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("ARM64 Android runtime required", libraryDirectory.name == "arm64")
        val qemu = File(libraryDirectory, "libqemu-system-aarch64.so")
        assertTrue("Packaged ARM64 QEMU is missing", qemu.isFile && qemu.canExecute())

        val assets = GuestAssetInstaller.forContext(context).install()
        val lease = GuestDiskLease.acquire(assets.rootDisk)
        val qmpSocket = File(context.filesDir, "pocketos-runtime-validation.qmp")
        qmpSocket.delete()
        val launch = QemuLaunchConfig(
            executable = qemu,
            kernel = assets.kernel,
            initramfs = assets.initramfs,
            rootDisk = assets.rootDisk,
            qmpSocket = qmpSocket,
            memoryMiB = 512,
            vcpus = 1,
        )
        val probe = GuestReadinessProbe()
        val ready = CountDownLatch(1)
        var process: NativeProcessHandle? = null
        try {
            val started = NativeProcessSupervisor().start(
                command = launch.toCommand(),
                workingDirectory = context.filesDir,
                onSerialOutput = { bytes ->
                    if (probe.onSerialOutput(bytes)) ready.countDown()
                },
            )
            process = started
            assertTrue(
                "Alpine did not reach its first-run or login prompt within 120 seconds",
                ready.await(120, TimeUnit.SECONDS),
            )
            AndroidRuntimeValidation.recordGuestBootVerified(context)
        } finally {
            process?.let { running ->
                if (running.isAlive) {
                    runCatching { QmpClient().requestQuit(qmpSocket.absolutePath, timeoutMillis = 3_000) }
                    if (!running.awaitExit(8, TimeUnit.SECONDS)) running.forceStop()
                }
            }
            lease.close()
            qmpSocket.delete()
        }
    }
}

package com.pocketos.app

import android.content.ComponentName
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVmBridgeInstrumentationTest {
    @Test
    fun embeddedNativeBridgeLoads() {
        assertEquals("JNI_BRIDGE_READY;QEMU_NOT_LINKED", NativeVmBridge.status())
    }

    @Test
    fun packagedNativeExecutableRunsThroughTheVmSupervisor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executable = File(context.applicationInfo.nativeLibraryDir, "libpocketos_exec_probe.so")

        assertTrue("Probe executable missing: ${executable.absolutePath}", executable.isFile)
        assertTrue("Probe is not executable: ${executable.absolutePath}", executable.canExecute())

        val output = StringBuilder()
        val outputLock = Any()
        val outputReady = CountDownLatch(1)
        val supervisor = NativeProcessSupervisor()
        val process = supervisor.start(
            command = listOf(executable.absolutePath),
            onSerialOutput = { chunk ->
                synchronized(outputLock) {
                    output.append(String(chunk, Charsets.UTF_8))
                    if (output.contains("POCKETOS_EXEC_PROBE_OK")) {
                        outputReady.countDown()
                    }
                }
            },
        )

        assertTrue("Packaged probe process did not exit", process.awaitExit(10, TimeUnit.SECONDS))
        assertTrue("Packaged probe output was not received", outputReady.await(2, TimeUnit.SECONDS))
        assertEquals(0, process.exitCode)
        synchronized(outputLock) {
            assertEquals("POCKETOS_EXEC_PROBE_OK\n", output.toString())
        }
        assertTrue(!supervisor.isRunning())
    }

    @Test
    fun capturesChildFailureDiagnosticsAndNonzeroExitStatus() {
        val output = StringBuilder()
        val lock = Any()
        val diagnosticSeen = CountDownLatch(1)
        val process = NativeProcessSupervisor().start(
            command = listOf(
                "/system/bin/sh",
                "-c",
                "printf 'POCKETOS_CHILD_FAILURE_DIAGNOSTIC\\n' >&2; exit 23",
            ),
            onDiagnosticOutput = { bytes ->
                synchronized(lock) {
                    output.append(String(bytes, Charsets.UTF_8))
                    if (output.contains("POCKETOS_CHILD_FAILURE_DIAGNOSTIC")) diagnosticSeen.countDown()
                }
            },
        )

        assertTrue("Child process did not exit", process.awaitExit(10, TimeUnit.SECONDS))
        assertEquals("Unexpected child exit status", 23, process.exitCode)
        assertTrue("Child stderr diagnostics were not captured", diagnosticSeen.await(5, TimeUnit.SECONDS))
        synchronized(lock) {
            assertTrue(output.contains("POCKETOS_CHILD_FAILURE_DIAGNOSTIC"))
        }
    }

    @Test
    fun vmServiceRunsInDedicatedProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, ServerService::class.java),
            0
        )

        assertEquals("${context.packageName}:vm", serviceInfo.processName)
    }
}

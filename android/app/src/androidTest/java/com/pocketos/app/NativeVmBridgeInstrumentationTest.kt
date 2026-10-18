package com.pocketos.app

import android.content.ComponentName
import java.io.File
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
    fun packagedNativeExecutableCanRunFromInstalledLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executable = File(context.applicationInfo.nativeLibraryDir, "libpocketos_exec_probe.so")

        assertTrue("Probe executable missing: ${executable.absolutePath}", executable.isFile)
        assertTrue("Probe is not executable: ${executable.absolutePath}", executable.canExecute())

        val process = ProcessBuilder(executable.absolutePath)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        assertTrue("Packaged probe process did not exit", completed)

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue())
        assertEquals("POCKETOS_EXEC_PROBE_OK", output)
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

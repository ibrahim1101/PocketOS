package com.pocketos.app

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVmBridgeInstrumentationTest {
    @Test
    fun embeddedNativeBridgeLoads() {
        assertEquals("JNI_BRIDGE_READY;QEMU_NOT_LINKED", NativeVmBridge.status())
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

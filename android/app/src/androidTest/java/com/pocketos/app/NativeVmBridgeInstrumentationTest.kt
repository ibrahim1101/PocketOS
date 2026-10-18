package com.pocketos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVmBridgeInstrumentationTest {
    @Test
    fun embeddedNativeBridgeLoads() {
        assertEquals("JNI_BRIDGE_READY;QEMU_NOT_LINKED", NativeVmBridge.status())
    }
}

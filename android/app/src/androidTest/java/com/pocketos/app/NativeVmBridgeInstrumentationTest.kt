package com.pocketos.app

import android.test.InstrumentationTestCase

class NativeVmBridgeInstrumentationTest : InstrumentationTestCase() {
    fun testEmbeddedNativeBridgeLoads() {
        assertEquals("JNI_BRIDGE_READY;QEMU_NOT_LINKED", NativeVmBridge.status())
    }
}

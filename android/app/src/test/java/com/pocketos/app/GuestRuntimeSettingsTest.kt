package com.pocketos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestRuntimeSettingsTest {
    @Test fun appliesDefaultsAndAcceptsUpperBounds() {
        val defaults = GuestRuntimeSettings()
        assertEquals(1024, defaults.memoryMiB)
        assertEquals(2, defaults.vcpus)
        assertEquals(18080, defaults.hostHttpPort)
        GuestRuntimeSettings(memoryMiB = 4096, vcpus = 4, hostHttpPort = 65535)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMemoryOutsideRange() { GuestRuntimeSettings(memoryMiB = 256) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCpuOutsideRange() { GuestRuntimeSettings(vcpus = 8) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivilegedHostPort() { GuestRuntimeSettings(hostHttpPort = 80) }
}

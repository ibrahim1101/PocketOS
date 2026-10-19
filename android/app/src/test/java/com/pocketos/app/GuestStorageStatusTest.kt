package com.pocketos.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestStorageStatusTest {
    @Test
    fun reportsImageCapacitySeparatelyFromDeviceFreeSpace() {
        val status = GuestStorageStatus(
            diskCapacityBytes = 256L * 1024 * 1024,
            availableDeviceBytes = 3L * 1024 * 1024 * 1024,
        )

        assertEquals("256.0 MiB", status.diskCapacityLabel)
        assertEquals("3.0 GiB", status.availableDeviceLabel)
        assertFalse(status.lowDeviceSpace)
    }

    @Test
    fun missingImageIsNotReportedAsAnEmptyGuestDisk() {
        val status = GuestStorageStatus(diskCapacityBytes = null, availableDeviceBytes = 0)

        assertEquals("Not initialized", status.diskCapacityLabel)
        assertTrue(status.lowDeviceSpace)
    }

    @Test
    fun readUsesExistingImageLengthAndDoesNotCreateFiles() {
        val directory = createTempDir()
        try {
            val disk = File(directory, "rootfs.ext4")
            disk.writeBytes(ByteArray(4096))

            val status = GuestStorageStatus.read(disk, directory)

            assertEquals(4096L, status.diskCapacityBytes)
            assertTrue(status.availableDeviceBytes >= 0)
            assertTrue(disk.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

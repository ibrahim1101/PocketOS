package com.pocketos.app

import java.io.File
import java.io.RandomAccessFile
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
        assertEquals("Unavailable while disk is active or unreadable", status.guestFreeSpaceLabel)
    }

    @Test
    fun readsExt4FreeSpaceOnlyWhileItCanAcquireTheExclusiveDiskLease() {
        val directory = createTempDir()
        try {
            val disk = ext4Image(directory)
            val status = GuestStorageStatus.read(disk, directory)

            assertEquals(4096L, status.diskCapacityBytes)
            assertEquals(1024L, status.guestFileSystem?.freeBytes)
            assertEquals("1.0 KiB", status.guestFreeSpaceLabel)
            assertEquals("3.0 KiB", status.guestFileSystem?.usedLabel)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsOverflowing64BitExt4CapacityAndFreeCounts() {
        val directory = createTempDir()
        try {
            val disk = ext4Image(directory)
            RandomAccessFile(disk, "rw").use { image ->
                writeUInt32LE(image, 1024 + 96, 0x80)
                writeUInt32LE(image, 1024 + 0x150, 0xFFFFFFFF)
                writeUInt32LE(image, 1024 + 0x158, 0xFFFFFFFF)
            }

            val status = GuestStorageStatus.read(disk, directory)

            assertEquals(4096L, status.diskCapacityBytes)
            assertEquals(null, status.guestFileSystem)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun leavesGuestFreeSpaceUnknownWhenTheDiskIsLeased() {
        val directory = createTempDir()
        try {
            val disk = ext4Image(directory)
            val lease = GuestDiskLease.acquire(disk)
            try {
                val status = GuestStorageStatus.read(disk, directory)
                assertEquals("Unavailable while disk is active or unreadable", status.guestFreeSpaceLabel)
            } finally {
                lease.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ext4Image(directory: File): File {
        val disk = File(directory, "rootfs.ext4")
        RandomAccessFile(disk, "rw").use { image ->
            image.setLength(4096)
            writeUInt32LE(image, 1024 + 4, 4)
            writeUInt32LE(image, 1024 + 12, 1)
            writeUInt32LE(image, 1024 + 24, 0)
            image.seek(1024 + 56)
            image.write(0x53)
            image.write(0xEF)
        }
        return disk
    }

    private fun writeUInt32LE(file: RandomAccessFile, offset: Long, value: Long) {
        file.seek(offset)
        repeat(4) { index -> file.write(((value ushr (index * 8)) and 0xFF).toInt()) }
    }
}

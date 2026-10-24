package com.pocketos.app

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDiskLeaseTest {
    @Test
    fun preventsConcurrentWritersAndReleasesLeaseOnClose() {
        val directory = Files.createTempDirectory("pocketos-disk-lock").toFile()
        try {
            val disk = directory.resolve("rootfs.ext4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val first = GuestDiskLease.acquire(disk)

            var rejected = false
            try {
                GuestDiskLease.acquire(disk)
            } catch (_: GuestDiskInUseException) {
                rejected = true
            }
            assertTrue("Second disk writer should be rejected", rejected)

            first.close()
            val second = GuestDiskLease.acquire(disk)
            assertEquals(disk.absolutePath, second.disk.absolutePath)
            second.close()
            assertTrue(directory.resolve("rootfs.ext4.lock").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingGuestDisk() {
        val directory = Files.createTempDirectory("pocketos-disk-lock").toFile()
        try {
            GuestDiskLease.acquire(directory.resolve("missing.ext4"))
        } finally {
            directory.deleteRecursively()
        }
    }
}

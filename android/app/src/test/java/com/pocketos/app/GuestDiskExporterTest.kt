package com.pocketos.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GuestDiskExporterTest {
    @Test
    fun exportsExt4ImageByteForByteAndReleasesLease() {
        val directory = createTempDir()
        try {
            val disk = ext4Image(directory)
            val destination = ByteArrayOutputStream()

            val copied = GuestDiskExporter.export(disk) { destination }

            assertEquals(disk.length(), copied)
            assertArrayEquals(disk.readBytes(), destination.toByteArray())
            val nextLease = GuestDiskLease.acquire(disk)
            nextLease.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToExportWhileGuestOwnsDiskWithoutOpeningDestination() {
        val directory = createTempDir()
        try {
            val disk = ext4Image(directory)
            val guestLease = GuestDiskLease.acquire(disk)
            var destinationOpened = false
            try {
                assertThrows(GuestDiskInUseException::class.java) {
                    GuestDiskExporter.export(disk) {
                        destinationOpened = true
                        OutputStream.nullOutputStream()
                    }
                }
                assertFalse(destinationOpened)
            } finally {
                guestLease.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ext4Image(directory: File): File {
        val disk = File(directory, "rootfs.ext4")
        val bytes = ByteArray(4096)
        bytes[1080] = 0x53
        bytes[1081] = 0xEF.toByte()
        disk.writeBytes(bytes)
        return disk
    }
}

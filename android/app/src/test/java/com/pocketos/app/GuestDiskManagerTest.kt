package com.pocketos.app
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDiskManagerTest {
    @Test fun growsOnlyWhenStoppedAndPreservesDiskBytes() {
        val dir = createTempDir(prefix = "pocketos-grow")
        try {
            val disk = ext4(dir, "rootfs.ext4", 4096)
            disk.writeBytes(disk.readBytes().also { it[2048] = 0x5a })
            GuestDiskManager.grow(disk, 1024L * 1024L)
            assertEquals(1024L * 1024L, disk.length())
            assertEquals(0x5a.toByte(), disk.readBytes()[2048])
            var blocked = false
            GuestDiskLease.acquire(disk).use {
                try { GuestDiskManager.grow(disk, 2L * 1024L * 1024L) }
                catch (_: GuestDiskInUseException) { blocked = true }
            }
            assertTrue(blocked)
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidRestoreLeavesCurrentDiskUnchanged() {
        val dir = createTempDir(prefix = "pocketos-restore")
        try {
            val disk = ext4(dir, "rootfs.ext4", 1024 * 1024)
            val original = disk.readBytes()
            var rejected = false
            try { GuestDiskManager.restore(disk) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) } }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(original.contentEquals(disk.readBytes()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsOverflowing64BitExt4BackupWithoutReplacingCurrentDisk() {
        val dir = createTempDir(prefix = "pocketos-restore-overflow")
        try {
            val disk = ext4(dir, "rootfs.ext4", 1024 * 1024)
            val original = disk.readBytes()
            val backupFile = ext4(dir, "overflow.ext4", 1024 * 1024)
            RandomAccessFile(backupFile, "rw").use { image ->
                image.seek(1120)
                image.write(byteArrayOf(0x80.toByte(), 0, 0, 0))
                image.seek(1360)
                image.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            }

            var rejected = false
            try { GuestDiskManager.restore(disk) { backupFile.inputStream() } }
            catch (_: IllegalArgumentException) { rejected = true }

            assertTrue(rejected)
            assertTrue(original.contentEquals(disk.readBytes()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun restoresValidatedExt4Backup() {
        val dir = createTempDir(prefix = "pocketos-restore")
        try {
            val disk = ext4(dir, "rootfs.ext4", 1024 * 1024)
            val backup = ext4(dir, "backup.ext4", 2 * 1024 * 1024).readBytes()
            GuestDiskManager.restore(disk) { ByteArrayInputStream(backup) }
            assertTrue(backup.contentEquals(disk.readBytes()))
        } finally { dir.deleteRecursively() }
    }

    private fun ext4(dir: File, name: String, size: Int): File {
        val file = File(dir, name)
        RandomAccessFile(file, "rw").use {
            it.setLength(size.toLong())
            it.seek(1080); it.write(0x53); it.write(0xEF)
            it.seek(1028)
            val blocks = size / 1024
            it.write(byteArrayOf(blocks.toByte(), (blocks ushr 8).toByte(), (blocks ushr 16).toByte(), (blocks ushr 24).toByte()))
            it.seek(1048); it.write(byteArrayOf(0, 0, 0, 0)); it.fd.sync()
        }
        return file
    }
}

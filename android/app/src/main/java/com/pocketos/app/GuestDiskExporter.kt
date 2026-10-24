package com.pocketos.app

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Copies a persistent ext4 guest image only while it is exclusively leased.
 * The destination is requested after acquiring the lease, so a running VM
 * cannot leave behind a newly created but incomplete export.
 */
object GuestDiskExporter {
    fun export(disk: File, openDestination: () -> OutputStream): Long {
        GuestDiskLease.acquire(disk).use {
            require(isExt4(disk)) { "Guest disk is not a valid ext4 image" }
            openDestination().use { output ->
                var copied = 0L
                FileInputStream(disk).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                }
                output.flush()
                return copied
            }
        }
    }

    private fun isExt4(disk: File): Boolean {
        if (!disk.isFile || disk.length() < EXT4_MAGIC_OFFSET + 2) return false
        return RandomAccessFile(disk, "r").use { image ->
            image.seek(EXT4_MAGIC_OFFSET)
            image.readUnsignedByte() == 0x53 && image.readUnsignedByte() == 0xEF
        }
    }

    private const val EXT4_MAGIC_OFFSET = 1024L + 56L
}

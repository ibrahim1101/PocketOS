package com.pocketos.app

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Copies a persistent ext4 guest image only while it is exclusively leased.
 * The destination is requested after acquiring the lease, so a running VM
 * cannot leave behind a newly created but incomplete export.
 */
object GuestDiskExporter {
    fun export(disk: File, openDestination: () -> OutputStream): Long {
        GuestDiskLease.acquire(disk).use {
            require(GuestDiskManager.ext4Capacity(disk) != null) {
                "Guest disk is not a valid ext4 image or its filesystem exceeds the image size"
            }
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
}

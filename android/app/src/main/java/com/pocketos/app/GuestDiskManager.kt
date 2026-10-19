package com.pocketos.app
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Safe stopped-VM operations for the private raw ext4 guest image. */
object GuestDiskManager {
    private const val RESERVE = 32L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val MAGIC = 1080L
    private const val LOG_BLOCK = 1048L
    private const val BLOCKS = 1028L
    private const val INCOMPAT = 1120L
    private const val BLOCKS_HIGH = 1024L + 0x150L
    private const val FEATURE_64BIT = 0x80L

    /** Stages, validates, and atomically replaces an ext4 backup under the exclusive disk lease. */
    fun restore(disk: File, source: () -> InputStream): Long {
        val target = disk.absoluteFile
        val parent = target.parentFile ?: error("Guest disk directory is unavailable")
        require(target.isFile && ext4Capacity(target) != null) { "Current guest disk is missing or invalid" }
        GuestDiskLease.acquire(target).use {
            val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.restore")
            try {
                val limit = (parent.usableSpace - RESERVE).coerceAtLeast(0L)
                require(limit > 0) { "Not enough free device storage for a safe disk restore" }
                var copied = 0L
                source().use { input ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count.toLong() <= limit - copied) { "Backup is larger than available device storage" }
                            output.write(buffer, 0, count)
                            copied += count
                        }
                        output.fd.sync()
                    }
                }
                require(ext4Capacity(staging) != null) {
                    "Selected backup is not a valid ext4 image or its filesystem exceeds the image size"
                }
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return copied
            } finally {
                if (staging.exists()) staging.delete()
            }
        }
    }

    /** Grows the raw image while stopped; resize2fs expands ext4 during the next guest boot. */
    fun grow(disk: File, newCapacityBytes: Long): Long {
        val target = disk.absoluteFile
        val parent = target.parentFile ?: error("Guest disk directory is unavailable")
        require(target.isFile) { "Guest disk is missing" }
        GuestDiskLease.acquire(target).use {
            require(ext4Capacity(target) != null) { "Guest disk is not a valid ext4 image" }
            require(newCapacityBytes > target.length()) { "Guest disk can only be grown" }
            require(newCapacityBytes % MIB == 0L) { "New disk size must be aligned to MiB" }
            require(newCapacityBytes <= parent.usableSpace - RESERVE) {
                "Not enough free device storage for a safe disk growth operation"
            }
            val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.grow")
            try {
                FileInputStream(target).use { input ->
                    FileOutputStream(staging).use { output -> input.copyTo(output); output.fd.sync() }
                }
                RandomAccessFile(staging, "rw").use { it.setLength(newCapacityBytes); it.fd.sync() }
                require(ext4Capacity(staging) != null) { "Expanded guest image failed ext4 validation" }
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return newCapacityBytes
            } finally {
                if (staging.exists()) staging.delete()
            }
        }
    }

    private fun ext4Capacity(file: File): Long? {
        if (!file.isFile || file.length() < MAGIC + 2) return null
        return runCatching {
            RandomAccessFile(file, "r").use { image ->
                image.seek(MAGIC)
                if (image.readUnsignedByte() != 0x53 || image.readUnsignedByte() != 0xEF) return null
                val logBlock = read32(image, LOG_BLOCK)
                if (logBlock > 6) return null
                val incompat = read32(image, INCOMPAT)
                var blocks = read32(image, BLOCKS)
                if (incompat and FEATURE_64BIT != 0L) blocks += read32(image, BLOCKS_HIGH) shl 32
                if (blocks <= 0) return null
                (blocks * (1024L shl logBlock.toInt())).takeIf { it <= file.length() }
            }
        }.getOrNull()
    }

    private fun read32(file: RandomAccessFile, offset: Long): Long {
        file.seek(offset)
        return file.readUnsignedByte().toLong() or (file.readUnsignedByte().toLong() shl 8) or
            (file.readUnsignedByte().toLong() shl 16) or (file.readUnsignedByte().toLong() shl 24)
    }
}

package com.pocketos.app

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

data class GuestFileSystemSpace(
    val capacityBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (capacityBytes - freeBytes).coerceAtLeast(0)
    val freeLabel: String get() = GuestStorageStatus.formatBytes(freeBytes)
    val usedLabel: String get() = GuestStorageStatus.formatBytes(usedBytes)
}

/** Host-side view of the persistent raw guest image and device storage. */
data class GuestStorageStatus(
    val diskCapacityBytes: Long?,
    val availableDeviceBytes: Long,
    val guestFileSystem: GuestFileSystemSpace? = null,
) {
    val diskCapacityLabel: String
        get() = diskCapacityBytes?.let(::formatBytes) ?: "Not initialized"
    val availableDeviceLabel: String
        get() = formatBytes(availableDeviceBytes.coerceAtLeast(0))
    val lowDeviceSpace: Boolean
        get() = availableDeviceBytes < LOW_DEVICE_SPACE_BYTES
    val guestFreeSpaceLabel: String
        get() = guestFileSystem?.freeLabel ?: "Unavailable while disk is active or unreadable"

    companion object {
        private const val KIB = 1024L
        private const val MIB = KIB * 1024
        private const val GIB = MIB * 1024
        const val LOW_DEVICE_SPACE_BYTES = 512L * MIB
        private const val SUPERBLOCK_OFFSET = 1024L
        private const val EXT4_MAGIC_OFFSET = SUPERBLOCK_OFFSET + 56
        private const val FEATURE_INCOMPAT_OFFSET = SUPERBLOCK_OFFSET + 96
        private const val BLOCKS_COUNT_HIGH_OFFSET = SUPERBLOCK_OFFSET + 0x150
        private const val FREE_BLOCKS_COUNT_HIGH_OFFSET = SUPERBLOCK_OFFSET + 0x158
        private const val EXT4_FEATURE_INCOMPAT_64BIT = 0x80L

        fun read(rootDisk: File, appFilesDirectory: File): GuestStorageStatus {
            val capacity = rootDisk.takeIf { it.isFile && it.length() > 0 }?.length()
            val fsSpace = if (capacity == null) null else runCatching {
                GuestDiskLease.acquire(rootDisk).use { readExt4Space(rootDisk) }
            }.getOrNull()
            return GuestStorageStatus(
                diskCapacityBytes = capacity,
                availableDeviceBytes = appFilesDirectory.usableSpace.coerceAtLeast(0),
                guestFileSystem = fsSpace,
            )
        }

        private fun readExt4Space(disk: File): GuestFileSystemSpace? {
            if (disk.length() < EXT4_MAGIC_OFFSET + 2) return null
            RandomAccessFile(disk, "r").use { image ->
                image.seek(EXT4_MAGIC_OFFSET)
                if (image.readUnsignedByte() != 0x53 || image.readUnsignedByte() != 0xEF) return null

                val logBlockSize = readUInt32LE(image, SUPERBLOCK_OFFSET + 24)
                if (logBlockSize > 6) return null
                val blockSize = 1024L shl logBlockSize.toInt()
                val incompatFeatures = readUInt32LE(image, FEATURE_INCOMPAT_OFFSET)
                val maximumBlocksInImage = disk.length() / blockSize
                val totalLow = readUInt32LE(image, SUPERBLOCK_OFFSET + 4)
                val freeLow = readUInt32LE(image, SUPERBLOCK_OFFSET + 12)
                val totalHigh: Long
                val freeHigh: Long
                if (incompatFeatures and EXT4_FEATURE_INCOMPAT_64BIT != 0L) {
                    totalHigh = readUInt32LE(image, BLOCKS_COUNT_HIGH_OFFSET)
                    freeHigh = readUInt32LE(image, FREE_BLOCKS_COUNT_HIGH_OFFSET)
                } else {
                    totalHigh = 0
                    freeHigh = 0
                }
                if (totalHigh > (maximumBlocksInImage ushr 32) ||
                    freeHigh > (maximumBlocksInImage ushr 32)
                ) return null
                val totalBlocks = (totalHigh shl 32) or totalLow
                val freeBlocks = (freeHigh shl 32) or freeLow
                if (totalBlocks <= 0 || totalBlocks > maximumBlocksInImage || freeBlocks > totalBlocks) return null
                return GuestFileSystemSpace(totalBlocks * blockSize, freeBlocks * blockSize)
            }
        }

        private fun readUInt32LE(file: RandomAccessFile, offset: Long): Long {
            file.seek(offset)
            val b0 = file.readUnsignedByte().toLong()
            val b1 = file.readUnsignedByte().toLong()
            val b2 = file.readUnsignedByte().toLong()
            val b3 = file.readUnsignedByte().toLong()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun formatBytes(bytes: Long): String {
            val safeBytes = bytes.coerceAtLeast(0)
            return when {
                safeBytes >= GIB -> String.format(Locale.US, "%.1f GiB", safeBytes.toDouble() / GIB)
                safeBytes >= MIB -> String.format(Locale.US, "%.1f MiB", safeBytes.toDouble() / MIB)
                safeBytes >= KIB -> String.format(Locale.US, "%.1f KiB", safeBytes.toDouble() / KIB)
                else -> "$safeBytes B"
            }
        }
    }
}

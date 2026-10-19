package com.pocketos.app

import java.io.File
import java.util.Locale

/** Host-side view of the persistent raw guest image and device storage. */
data class GuestStorageStatus(
    val diskCapacityBytes: Long?,
    val availableDeviceBytes: Long,
) {
    val diskCapacityLabel: String
        get() = diskCapacityBytes?.let(::formatBytes) ?: "Not initialized"
    val availableDeviceLabel: String
        get() = formatBytes(availableDeviceBytes.coerceAtLeast(0))
    val lowDeviceSpace: Boolean
        get() = availableDeviceBytes < LOW_DEVICE_SPACE_BYTES

    companion object {
        private const val KIB = 1024L
        private const val MIB = KIB * 1024
        private const val GIB = MIB * 1024
        const val LOW_DEVICE_SPACE_BYTES = 512L * MIB

        fun read(rootDisk: File, appFilesDirectory: File): GuestStorageStatus =
            GuestStorageStatus(
                diskCapacityBytes = rootDisk.takeIf { it.isFile && it.length() > 0 }?.length(),
                availableDeviceBytes = appFilesDirectory.usableSpace.coerceAtLeast(0),
            )

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

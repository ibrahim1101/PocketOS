package com.pocketos.app

/**
 * A deliberately narrow, sanitized support snapshot. It accepts only host/runtime metadata and
 * never includes guest serial output, terminal input, credentials, file paths, or network addresses.
 */
object DiagnosticsReport {
    fun format(
        vmStatus: String,
        supportedAbis: List<String>,
        androidApi: Int,
        runtimeGateReason: String,
        qemuExecutionVerified: Boolean,
        guestBootVerified: Boolean,
        availableHostMemoryMiB: Long,
        diskCapacity: String,
        deviceFreeSpace: String,
        guestFreeSpace: String,
        lanExposureEnabled: Boolean,
    ): String {
        val status = when {
            vmStatus.contains("running", ignoreCase = true) -> "Running"
            vmStatus.contains("starting", ignoreCase = true) -> "Starting"
            vmStatus.contains("stopped", ignoreCase = true) -> "Stopped"
            vmStatus.contains("blocked", ignoreCase = true) -> "Blocked"
            else -> "Unknown"
        }
        val gateReason = when (runtimeGateReason) {
            "requires an ARM64 Android runtime",
            "QEMU execution has not been verified on Android",
            "Alpine guest boot has not been verified on Android",
            "ARM64 runtime validation passed" -> runtimeGateReason
            else -> "Unavailable"
        }
        return listOf(
            "PocketOS diagnostics",
            "VM status: $status",
            "Supported ABIs: ${supportedAbis.joinToString()}",
            "Android API: $androidApi",
            "Runtime gate: $gateReason",
            "QEMU execution evidence: $qemuExecutionVerified",
            "Guest boot evidence: $guestBootVerified",
            "Available host memory: ${availableHostMemoryMiB.coerceAtLeast(0)} MiB",
            "Persistent guest disk capacity: $diskCapacity",
            "Device free space: $deviceFreeSpace",
            "Guest filesystem free space: $guestFreeSpace",
            "Network exposure: " + if (lanExposureEnabled) "Wi-Fi LAN (active IPv4 only)" else "Loopback only",
            "",
            "Terminal contents and credentials are excluded.",
        ).joinToString("\n")
    }
}

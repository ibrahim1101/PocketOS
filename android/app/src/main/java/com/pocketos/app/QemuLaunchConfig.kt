package com.pocketos.app

import java.io.File

/**
 * Produces the argument vector for the Android-hosted ARM64 Alpine guest.
 * Every argument remains separate so paths and kernel arguments are not shell-parsed.
 */
data class QemuLaunchConfig(
    val executable: File,
    val kernel: File,
    val initramfs: File,
    val rootDisk: File,
    val qmpSocket: File,
    val memoryMiB: Int = 1024,
    val vcpus: Int = 2,
    val hostHttpPort: Int = 18080,
    val guestHttpPort: Int = 8080,
    val hostBindAddress: String = "127.0.0.1",
) {
    init {
        require(memoryMiB >= 256) { "Guest memory must be at least 256 MiB" }
        require(vcpus in 1..8) { "Guest vCPU count must be between 1 and 8" }
        require(hostHttpPort in 1..65535) { "Host HTTP port is invalid" }
        require(guestHttpPort in 1..65535) { "Guest HTTP port is invalid" }
        require(HostIpv4Address.isValid(hostBindAddress)) { "Host bind address must be an IPv4 address" }
    }

    fun toCommand(): List<String> = listOf(
        executable.absolutePath,
        "-machine", "virt",
        "-accel", "tcg",
        // Match the known-good host guest smoke-test CPU model for predictable Linux features.
        "-cpu", "cortex-a72",
        "-smp", vcpus.toString(),
        "-m", memoryMiB.toString(),
        "-kernel", kernel.absolutePath,
        "-initrd", initramfs.absolutePath,
        "-append", "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rw rootwait",
        "-drive", "if=none,id=rootfs,file=${rootDisk.absolutePath},format=raw",
        "-device", "virtio-blk-device,drive=rootfs",
        "-netdev", "user,id=net0,hostfwd=tcp:$hostBindAddress:$hostHttpPort-:$guestHttpPort",
        "-device", "virtio-net-device,netdev=net0",
        "-display", "none",
        "-serial", "stdio",
        "-monitor", "none",
        "-qmp", "unix:${qmpSocket.absolutePath},server=on,wait=off",
        "-no-reboot",
    )

    /** Stable launch facts for logs; filesystem paths and any future secret arguments stay out. */
    fun diagnosticSummary(): String = buildString {
        append("machine=virt accel=tcg cpu=cortex-a72 vcpus=$vcpus memoryMiB=$memoryMiB ")
        append("kernel=<app-private>/").append(kernel.name).append(' ')
        append("initramfs=<app-private>/").append(initramfs.name).append(' ')
        append("disk=raw-virtio-blk:<app-private>/").append(rootDisk.name).append(' ')
        append("console=ttyAMA0 serial=stdio qmp=unix:<app-private>/").append(qmpSocket.name).append(' ')
        append("root=/dev/vda rootfstype=ext4 rw rootwait ")
        append("net=user hostBind=$hostBindAddress hostPort=$hostHttpPort guestPort=$guestHttpPort noReboot=true")
    }
}


object HostIpv4Address {
    fun isValid(value: String): Boolean {
        val octets = value.split(".")
        if (octets.size != 4) return false
        val values = octets.map { octet ->
            if (octet.isEmpty() || !octet.all { it in '0'..'9' } ||
                (octet != "0" && octet.startsWith("0"))
            ) return false
            octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        if (values.all { it == 0 }) return false
        if (values.first() in 224..239) return false
        return true
    }
}

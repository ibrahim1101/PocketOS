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
) {
    init {
        require(memoryMiB >= 256) { "Guest memory must be at least 256 MiB" }
        require(vcpus in 1..8) { "Guest vCPU count must be between 1 and 8" }
        require(hostHttpPort in 1..65535) { "Host HTTP port is invalid" }
        require(guestHttpPort in 1..65535) { "Guest HTTP port is invalid" }
    }

    fun toCommand(): List<String> = listOf(
        executable.absolutePath,
        "-machine", "virt",
        "-accel", "tcg",
        "-cpu", "max",
        "-smp", vcpus.toString(),
        "-m", memoryMiB.toString(),
        "-kernel", kernel.absolutePath,
        "-initrd", initramfs.absolutePath,
        "-append", "console=ttyAMA0 root=/dev/vda rw rootwait",
        "-drive", "if=none,id=rootfs,file=${rootDisk.absolutePath},format=raw",
        "-device", "virtio-blk-device,drive=rootfs",
        "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$hostHttpPort-:$guestHttpPort",
        "-device", "virtio-net-device,netdev=net0",
        "-display", "none",
        "-serial", "stdio",
        "-monitor", "none",
        "-qmp", "unix:${qmpSocket.absolutePath},server=on,wait=off",
        "-no-reboot",
    )
}

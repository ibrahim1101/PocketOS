package com.pocketos.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuLaunchConfigTest {
    private fun config(
        hostPort: Int = 18080,
        guestPort: Int = 8080,
    ) = QemuLaunchConfig(
        executable = File("/data/app/lib/arm64/libqemu-system-aarch64.so"),
        kernel = File("/data/user/0/com.pocketos.app/files/vm/vmlinuz"),
        initramfs = File("/data/user/0/com.pocketos.app/files/vm/initramfs"),
        rootDisk = File("/data/user/0/com.pocketos.app/files/vm/rootfs.ext4"),
        qmpSocket = File("/data/user/0/com.pocketos.app/files/vm/run/qmp.sock"),
        hostHttpPort = hostPort,
        guestHttpPort = guestPort,
    )

    @Test
    fun commandUsesTcgSerialPipesAndPersistentVirtioDisk() {
        val args = config().toCommand()

        assertEquals("/data/app/lib/arm64/libqemu-system-aarch64.so", args.first())
        assertTrue(args.containsAll(listOf("-machine", "virt", "-accel", "tcg", "-serial", "stdio")))
        assertTrue(args.containsAll(listOf("-kernel", "/data/user/0/com.pocketos.app/files/vm/vmlinuz")))
        assertTrue(args.containsAll(listOf("-initrd", "/data/user/0/com.pocketos.app/files/vm/initramfs")))
        assertTrue(args.contains("-drive"))
        assertTrue(args.any { it == "if=none,id=rootfs,file=/data/user/0/com.pocketos.app/files/vm/rootfs.ext4,format=raw" })
        assertTrue(args.contains("virtio-blk-device,drive=rootfs"))
        assertTrue(args.any { it.startsWith("unix:/data/user/0/com.pocketos.app/files/vm/run/qmp.sock,") })
    }

    @Test
    fun httpForwardIsExplicitlyLoopbackBound() {
        val args = config(hostPort = 18081, guestPort = 8081).toCommand()
        val network = args[args.indexOf("-netdev") + 1]

        assertEquals("user,id=net0,hostfwd=tcp:127.0.0.1:18081-:8081", network)
        assertFalse(network.contains("0.0.0.0"))
    }

    @Test
    fun rejectsInvalidResourceLimitsAndPorts() {
        val base = config()
        val badMemory = runCatching {
            base.copy(memoryMiB = 128)
        }
        val badPort = runCatching {
            base.copy(hostHttpPort = 70000)
        }

        assertTrue(badMemory.isFailure)
        assertTrue(badPort.isFailure)
    }
}

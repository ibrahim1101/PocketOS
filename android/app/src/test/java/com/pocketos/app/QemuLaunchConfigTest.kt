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
        bindAddress: String = "127.0.0.1",
    ) = QemuLaunchConfig(
        executable = File("/data/app/lib/arm64/libqemu-system-aarch64.so"),
        kernel = File("/data/user/0/com.pocketos.app/files/vm/vmlinuz"),
        initramfs = File("/data/user/0/com.pocketos.app/files/vm/initramfs"),
        rootDisk = File("/data/user/0/com.pocketos.app/files/vm/rootfs.ext4"),
        qmpSocket = File("/data/user/0/com.pocketos.app/files/vm/run/qmp.sock"),
        hostHttpPort = hostPort,
        guestHttpPort = guestPort,
        hostBindAddress = bindAddress,
    )

    @Test
    fun commandUsesTcgSerialPipesAndPersistentVirtioDisk() {
        val args = config().toCommand()

        assertEquals("/data/app/lib/arm64/libqemu-system-aarch64.so", args.first())
        assertTrue(args.containsAll(listOf("-machine", "virt", "-accel", "tcg", "-cpu", "cortex-a72", "-serial", "stdio")))
        assertEquals("console=ttyAMA0 root=/dev/vda rootfstype=ext4 rw rootwait", args[args.indexOf("-append") + 1])
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
    fun lanForwardUsesOnlyTheRequestedIpv4Address() {
        val args = config(bindAddress = "192.168.1.23").toCommand()
        val network = args[args.indexOf("-netdev") + 1]

        assertEquals("user,id=net0,hostfwd=tcp:192.168.1.23:18080-:8080", network)
        assertFalse(network.contains("0.0.0.0"))
    }

    @Test
    fun rejectsInvalidHostBindAddresses() {
        assertTrue(runCatching { config(bindAddress = "0.0.0.0") }.isFailure)
        assertTrue(runCatching { config(bindAddress = "192.168.1.256") }.isFailure)
        assertTrue(runCatching { config(bindAddress = "example.com") }.isFailure)
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

    @Test
    fun diagnosticSummaryKeepsBootFactsAndRedactsPrivatePaths() {
        val config = config()
        val summary = config.diagnosticSummary()

        assertTrue(summary.contains("machine=virt accel=tcg cpu=cortex-a72 vcpus=2"))
        assertTrue(summary.contains("console=ttyAMA0 serial=stdio"))
        assertTrue(summary.contains("root=/dev/vda rootfstype=ext4 rw rootwait"))
        assertTrue(summary.contains("hostBind=127.0.0.1"))
        assertTrue(summary.contains("<app-private>/vmlinuz"))
        assertFalse(summary.contains("/data/user/0"))
        assertFalse(summary.contains(config.qmpSocket.absolutePath))
    }
}

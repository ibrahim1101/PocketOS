package com.pocketos.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestAssetInstallerTest {
    @Test
    fun installsVerifiedAssetsAndPreservesPersistentDiskOnRepeat() {
        val temp = Files.createTempDirectory("pocketos-assets-test").toFile()
        try {
            val seed = fakeExt4(4096)
            val payloads = mapOf(
                "vmlinuz-virt" to byteArrayOf(1, 2, 3),
                "initramfs-virt" to byteArrayOf(4, 5, 6),
                "rootfs.ext4.gzip" to gzip(seed),
            )
            val manifest = payloads.entries.joinToString("\n") { (name, data) ->
                "${sha256(data)}  $name"
            }.plus("\n").toByteArray()
            val source = GuestAssetSource { name ->
                ByteArrayInputStream(if (name == "assets.sha256") manifest else payloads.getValue(name))
            }
            val installer = GuestAssetInstaller(source, temp, minimumFreeBytes = 0)

            val first = installer.install()
            assertTrue(first.seedDiskCreated)
            assertArrayEquals(payloads.getValue("vmlinuz-virt"), first.kernel.readBytes())
            assertArrayEquals(payloads.getValue("initramfs-virt"), first.initramfs.readBytes())
            assertArrayEquals(seed, first.rootDisk.readBytes())

            val userData = byteArrayOf(9, 8, 7)
            first.rootDisk.appendBytes(userData)
            val second = installer.install()
            assertFalse(second.seedDiskCreated)
            assertTrue(second.rootDisk.readBytes().takeLast(3) == userData.toList())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsBootAssetHashMismatch() {
        val temp = Files.createTempDirectory("pocketos-assets-test").toFile()
        try {
            val payloads = mapOf(
                "vmlinuz-virt" to byteArrayOf(1),
                "initramfs-virt" to byteArrayOf(2),
                "rootfs.ext4.gzip" to gzip(fakeExt4(4096)),
            )
            val manifest = payloads.entries.joinToString("\n") { (name, data) ->
                "${sha256(data)}  $name"
            }.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)).plus("\n").toByteArray()
            GuestAssetInstaller(
                GuestAssetSource { name ->
                    ByteArrayInputStream(if (name == "assets.sha256") manifest else payloads.getValue(name))
                },
                temp,
                minimumFreeBytes = 0,
            ).install()
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun fakeExt4(size: Int): ByteArray = ByteArray(size).apply {
        this[1080] = 0x53
        this[1081] = 0xEF.toByte()
    }

    private fun gzip(content: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(content) }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.pocketos.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

fun interface GuestAssetSource {
    fun open(name: String): InputStream
}

data class InstalledGuestAssets(
    val kernel: File,
    val initramfs: File,
    val rootDisk: File,
    val seedDiskCreated: Boolean,
)

/** Installs immutable boot assets and initializes the persistent disk only once. */
class GuestAssetInstaller(
    private val assetSource: GuestAssetSource,
    private val filesDirectory: File,
    private val minimumFreeBytes: Long = MINIMUM_FREE_BYTES,
) {
    fun install(): InstalledGuestAssets {
        val manifestBytes = assetSource.open(MANIFEST).use { it.readBytes() }
        val manifest = parseManifest(manifestBytes)
        val versionId = sha256(manifestBytes)
        val guestDirectory = File(filesDirectory, "guest")
        val versionsDirectory = File(guestDirectory, "versions")
        check(versionsDirectory.mkdirs() || versionsDirectory.isDirectory) {
            "Could not create guest asset directory"
        }

        val versionDirectory = File(versionsDirectory, versionId)
        if (versionDirectory.exists()) {
            verifyInstalledVersion(versionDirectory, manifest)
        } else {
            installBootAssets(versionDirectory, manifest, manifestBytes)
        }

        val rootDisk = File(guestDirectory, ROOT_DISK)
        val seedDiskCreated = if (rootDisk.exists()) {
            check(GuestDiskManager.ext4Capacity(rootDisk) != null) {
                "Existing guest disk is not valid ext4; preserving it"
            }
            false
        } else {
            check(filesDirectory.usableSpace >= minimumFreeBytes) {
                "Not enough free app storage to initialize the Linux disk"
            }
            installSeedDisk(rootDisk, manifest.getValue(COMPRESSED_DISK))
        }

        return InstalledGuestAssets(
            kernel = File(versionDirectory, KERNEL),
            initramfs = File(versionDirectory, INITRAMFS),
            rootDisk = rootDisk,
            seedDiskCreated = seedDiskCreated,
        )
    }

    private fun installBootAssets(
        directory: File,
        manifest: Map<String, String>,
        manifestBytes: ByteArray,
    ) {
        val staging = File(directory.parentFile, ".${directory.name}.tmp")
        if (staging.exists()) staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not stage guest boot assets" }
        try {
            listOf(KERNEL, INITRAMFS).forEach { name ->
                copyVerified(name, manifest.getValue(name), File(staging, name))
            }
            FileOutputStream(File(staging, MANIFEST)).use { stream ->
                stream.write(manifestBytes)
                stream.fd.sync()
            }
            moveAtomically(staging, directory)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun verifyInstalledVersion(directory: File, manifest: Map<String, String>) {
        listOf(KERNEL, INITRAMFS).forEach { name ->
            val file = File(directory, name)
            check(file.isFile && sha256(file) == manifest.getValue(name)) {
                "Installed guest boot asset failed integrity verification: $name"
            }
        }
    }

    private fun installSeedDisk(destination: File, expectedHash: String): Boolean {
        val temporary = File(destination.parentFile, ".$ROOT_DISK.tmp")
        if (temporary.exists()) temporary.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(BufferedInputStream(assetSource.open(COMPRESSED_DISK)), digest).use { compressed ->
                GZIPInputStream(compressed).use { input ->
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
            }
            check(digest.digest().toHex() == expectedHash) {
                "Packaged guest asset failed integrity verification: $COMPRESSED_DISK"
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            check(GuestDiskManager.ext4Capacity(temporary) != null) {
                "Packaged Linux disk is not a valid ext4 image"
            }
            moveAtomically(temporary, destination)
            return true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun copyVerified(name: String, expectedHash: String, destination: File) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(BufferedInputStream(assetSource.open(name)), digest).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(digest.digest().toHex() == expectedHash) {
                "Packaged guest asset failed integrity verification: $name"
            }
            moveAtomically(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun parseManifest(bytes: ByteArray): Map<String, String> {
        val rows = bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .map { row ->
                val match = MANIFEST_ROW.matchEntire(row)
                    ?: error("Malformed guest asset checksum manifest")
                match.groupValues[2] to match.groupValues[1]
            }.toMap()
        check(rows.keys == REQUIRED_ASSETS) { "Guest asset manifest has missing or unexpected files" }
        return rows
    }

    private fun moveAtomically(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun forContext(context: Context): GuestAssetInstaller {
            val appContext = context.applicationContext
            return GuestAssetInstaller(
                assetSource = GuestAssetSource { name -> appContext.assets.open("pocketos/$name") },
                filesDirectory = appContext.filesDir,
            )
        }

        private const val MANIFEST = "assets.sha256"
        private const val KERNEL = "vmlinuz-virt"
        private const val INITRAMFS = "initramfs-virt"
        private const val COMPRESSED_DISK = "rootfs.ext4.gzip"
        private const val ROOT_DISK = "rootfs.ext4"
        private const val MINIMUM_FREE_BYTES = 288L * 1024L * 1024L
        private val REQUIRED_ASSETS = setOf(KERNEL, INITRAMFS, COMPRESSED_DISK)
        private val MANIFEST_ROW = Regex("^([0-9a-f]{64})  ([A-Za-z0-9._-]+)$")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

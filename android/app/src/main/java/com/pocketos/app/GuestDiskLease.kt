package com.pocketos.app

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

class GuestDiskInUseException(message: String) : IllegalStateException(message)

/**
 * Exclusive cross-process lease for the persistent guest disk.
 * The lock file is intentionally retained so all processes lock the same inode.
 */
class GuestDiskLease private constructor(
    val disk: File,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(disk: File): GuestDiskLease {
            val absoluteDisk = disk.absoluteFile
            check(absoluteDisk.isFile && absoluteDisk.length() > 0) {
                "Guest disk does not exist or is empty: $absoluteDisk"
            }
            val lockFile = File(absoluteDisk.parentFile, "${absoluteDisk.name}.lock")
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw GuestDiskInUseException("Guest disk is already in use: $absoluteDisk")
            }
            return GuestDiskLease(absoluteDisk, channel, lock)
        }
    }
}

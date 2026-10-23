package com.pocketos.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Private, bounded, timestamped evidence retained when Android kills instrumentation.
 *
 * Journal calls are deliberately non-blocking: app-private storage can stall on Android,
 * and diagnostics must never be able to suspend the guest wait or its watchdog. A daemon
 * writer persists queued records in the background without forcing a flash sync for every
 * heartbeat. The in-memory queue is capped so a stalled filesystem cannot grow memory use.
 */
internal class RuntimeTestJournal(
    context: Context,
    runId: String,
    private val appendBatch: (File, ByteArray) -> Unit = { target, bytes -> appendToFile(target, bytes) },
) {
    private val privateRoots = listOf(context.filesDir.absolutePath, context.cacheDir.absolutePath)
    val file = File(File(context.filesDir, "diagnostics"), "arm64-guest-$runId.log")
    private val queueLock = Object()
    private val pending = ArrayDeque<String>()
    private val writer = Thread({ writeLoop() }, "pocketos-runtime-journal").apply {
        isDaemon = true
        start()
    }

    fun record(kind: String, detail: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val safeKind = kind.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        val safeDetail = sanitize(detail).take(MAX_RECORD_CHARS)
        val line = "$stamp $safeKind $safeDetail\n"
        synchronized(queueLock) {
            if (pending.size >= MAX_QUEUED_RECORDS) {
                // Preserve milestones and failures when periodic heartbeats fill a stalled queue.
                val heartbeat = pending.firstOrNull { it.contains(" heartbeat ") || it.contains(" startup-heartbeat ") }
                if (heartbeat != null) pending.removeFirstOccurrence(heartbeat) else pending.removeFirst()
            }
            pending.addLast(line)
            queueLock.notifyAll()
        }
    }

    private fun writeLoop() {
        while (true) {
            val batch = synchronized(queueLock) {
                while (pending.isEmpty()) queueLock.wait()
                buildString {
                    repeat(minOf(pending.size, MAX_BATCH_RECORDS)) { append(pending.removeFirst()) }
                }
            }
            runCatching {
                val parent = file.parentFile ?: return@runCatching
                if (!parent.isDirectory && !parent.mkdirs()) return@runCatching
                if (file.length() + batch.length > MAX_FILE_BYTES) {
                    val previous = File(parent, file.name + ".previous")
                    previous.delete()
                    file.renameTo(previous)
                }
                appendBatch(file, batch.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun sanitize(value: String): String {
        var safe = SECRET_VALUE.replace(value) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
        privateRoots.forEach { root -> if (root.isNotBlank()) safe = safe.replace(root, "<app-private>") }
        return safe.replace(Regex("[\\r\\n]+"), " ")
    }

    private companion object {
        const val MAX_FILE_BYTES = 256 * 1024L
        const val MAX_RECORD_CHARS = 8_000
        const val MAX_QUEUED_RECORDS = 64
        const val MAX_BATCH_RECORDS = 16
        val SECRET_VALUE = Regex("(?i)(password|passwd|token|secret|private[_ -]?key)(\\s*[:=]\\s*)\\S+")

        fun appendToFile(file: File, bytes: ByteArray) {
            FileOutputStream(file, true).use { output -> output.write(bytes) }
        }
    }
}

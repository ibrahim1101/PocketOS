package com.pocketos.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Private, bounded, timestamped evidence retained when Android kills instrumentation. */
internal class RuntimeTestJournal(context: Context, runId: String) {
    private val privateRoots = listOf(context.filesDir.absolutePath, context.cacheDir.absolutePath)
    val file = File(File(context.filesDir, "diagnostics"), "arm64-guest-$runId.log")

    @Synchronized
    fun record(kind: String, detail: String) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val safeKind = kind.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        val safeDetail = sanitize(detail).take(MAX_RECORD_CHARS)
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                val previous = File(parent, file.name + ".previous")
                previous.delete()
                file.renameTo(previous)
            }
            FileOutputStream(file, true).use { stream ->
                stream.write("$stamp $safeKind $safeDetail\n".toByteArray(Charsets.UTF_8))
                stream.fd.sync()
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
        val SECRET_VALUE = Regex("(?i)(password|passwd|token|secret|private[_ -]?key)(\\s*[:=]\\s*)\\S+")
    }
}

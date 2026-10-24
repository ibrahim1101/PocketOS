package com.pocketos.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class DiagnosticEventType {
    START_REQUESTED,
    STOP_REQUESTED,
    RUNTIME_GATE_BLOCKED,
    STATE_CHANGED,
}

data class PocketOsDiagnosticEvent(
    val timestampMs: Long,
    val type: DiagnosticEventType,
    val state: VmState,
    val stopReason: GuestStopReason?,
) {
    fun safeSummary(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
        return "$stamp ${type.name} state=${state.name}" +
            (stopReason?.let { " reason=${it.name}" } ?: "")
    }
}

/**
 * Bounded, private event history. Values are enums only; no terminal bytes, command text,
 * credentials, file paths, network addresses, or arbitrary error messages are accepted.
 */
class DiagnosticsEventStore(private val file: File) {
    @Synchronized
    fun record(type: DiagnosticEventType, state: VmState, reason: GuestStopReason? = null) {
        val events = readAll().toMutableList()
        events += PocketOsDiagnosticEvent(System.currentTimeMillis(), type, state, reason)
        val parent = file.parentFile
        check(parent == null || parent.isDirectory || parent.mkdirs()) { "Cannot create diagnostics directory" }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(events.takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n") { encode(it) }, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.delete()
            error("Cannot replace diagnostics event history")
        }
        check(temp.renameTo(file)) {
            temp.delete()
            "Cannot commit diagnostics event history"
        }
    }

    @Synchronized
    fun readLatest(limit: Int = MAX_EVENTS): List<PocketOsDiagnosticEvent> {
        require(limit >= 0)
        return readAll().takeLast(limit)
    }

    private fun readAll(): List<PocketOsDiagnosticEvent> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull(::decode).takeLast(MAX_EVENTS)
        }.getOrDefault(emptyList())
    }

    private fun encode(event: PocketOsDiagnosticEvent): String =
        listOf(
            event.timestampMs.toString(),
            event.type.name,
            event.state.name,
            event.stopReason?.name.orEmpty(),
        ).joinToString("|")

    private fun decode(line: String): PocketOsDiagnosticEvent? = runCatching {
        val parts = line.split('|')
        require(parts.size == 4)
        val timestamp = parts[0].toLong()
        require(timestamp > 0)
        val type = DiagnosticEventType.valueOf(parts[1])
        val state = VmState.valueOf(parts[2])
        val reason = parts[3].takeIf(String::isNotEmpty)?.let(GuestStopReason::valueOf)
        PocketOsDiagnosticEvent(timestamp, type, state, reason)
    }.getOrNull()

    companion object {
        const val MAX_EVENTS = 100
    }
}

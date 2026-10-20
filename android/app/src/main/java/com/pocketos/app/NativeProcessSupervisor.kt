package com.pocketos.app

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Starts a native guest engine as a child process of the dedicated VM service.
 * QEMU serial uses stdin/stdout; stderr stays separate for diagnostics.
 */
class NativeProcessSupervisor : GuestProcessLauncher {
    @Volatile
    private var activeProcess: Process? = null

    @Synchronized
    override fun start(
        command: List<String>,
        workingDirectory: File?,
        onSerialOutput: (ByteArray) -> Unit,
        onDiagnosticOutput: (ByteArray) -> Unit,
    ): NativeProcessHandle {
        require(command.isNotEmpty()) { "Native process command cannot be empty" }
        check(activeProcess?.isAlive != true) { "A native VM process is already running" }

        val builder = ProcessBuilder(command)
        if (workingDirectory != null) {
            builder.directory(workingDirectory)
        }
        val process = builder.start()
        activeProcess = process
        pump(process.inputStream, "pocketos-serial", onSerialOutput)
        pump(process.errorStream, "pocketos-qemu-log", onDiagnosticOutput)
        return NativeProcessHandle(process)
    }

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    private fun pump(stream: InputStream, threadName: String, receiver: (ByteArray) -> Unit) {
        Thread({
            stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    receiver(buffer.copyOf(count))
                }
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
    }
}

class NativeProcessHandle internal constructor(private val process: Process) : GuestProcess {
    override val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitValue()

    @Synchronized
    override fun writeSerial(bytes: ByteArray) {
        check(process.isAlive) { "Native VM process is not running" }
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun awaitExit(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0) { "Timeout cannot be negative" }
        return process.waitFor(timeout, unit)
    }

    /** Last-resort process termination; callers should request QEMU shutdown first. */
    override fun forceStop(timeout: Long, unit: TimeUnit): Boolean {
        if (process.isAlive) {
            process.destroyForcibly()
        }
        return process.waitFor(timeout, unit)
    }

    /** Best-effort, bounded snapshot of procfs fields that help distinguish an idle guest from active TCG work. */
    fun diagnosticSnapshot(): String = runCatching {
        val ownerPid = android.os.Process.myPid()
        val childPids = readProcFile(File("/proc/$ownerPid/task/$ownerPid/children"))
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
        val qemuProcesses = childPids.asSequence()
            .map { File("/proc/$it") }
            .filter { directory -> readProcFile(File(directory, "cmdline")).contains(QEMU_EXECUTABLE_MARKER) }
            .take(MAX_DIAGNOSTIC_PROCESSES)
            .toList()
        if (qemuProcesses.isEmpty()) return "qemuProcfs=unavailable alive=$isAlive childList=$childPids"

        qemuProcesses.joinToString(prefix = "qemuProcfs=[", postfix = "]") { processDir ->
            val status = readProcFile(File(processDir, "status"))
            val statusFields = listOf(
                "State", "VmRSS", "VmSwap", "Threads", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches",
            ).mapNotNull { field ->
                status.lineSequence().firstOrNull { it.startsWith("$field:") }?.trim()
            }
            val tasks = File(processDir, "task").listFiles()
                ?.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
                ?.take(MAX_DIAGNOSTIC_THREADS)
                .orEmpty()
                .joinToString(prefix = "[", postfix = "]") { task ->
                    val taskStatus = readProcFile(File(task, "status"))
                    val name = taskStatus.lineSequence().firstOrNull { it.startsWith("Name:") }
                        ?.substringAfter(':')?.trim()?.take(32) ?: "?"
                    val state = taskStatus.lineSequence().firstOrNull { it.startsWith("State:") }
                        ?.substringAfter(':')?.trim()?.take(16) ?: "?"
                    val switches = taskStatus.lineSequence()
                        .firstOrNull { it.startsWith("voluntary_ctxt_switches:") }
                        ?.substringAfter(':')?.trim() ?: "?"
                    val wchan = readProcFile(File(task, "wchan")).ifEmpty { "?" }
                    val cpuTicks = readCpuTicks(File(task, "stat"))
                    "${task.name}:$name:$state:wchan=$wchan:cpu=$cpuTicks:vcsw=$switches"
                }
            "pid=${processDir.name} alive=$isAlive status=${statusFields.joinToString(",")}; threads=$tasks"
        }
    }.getOrElse { "qemuProcfs=unavailable alive=$isAlive reason=${it.javaClass.simpleName}" }

    private fun readCpuTicks(statFile: File): String = runCatching {
        val fields = statFile.readText().substringAfterLast(") ").trim().split(Regex("\\s+"))
        // After the parenthesized comm, fields[0] is state (field 3), followed by ppid through stime.
        "${fields[11]}+${fields[12]}"
    }.getOrElse { "?" }

    private fun readProcFile(file: File): String = runCatching { file.readText().trim() }.getOrElse { "unavailable" }

    private companion object {
        const val MAX_DIAGNOSTIC_THREADS = 8
        const val MAX_DIAGNOSTIC_PROCESSES = 2
        const val QEMU_EXECUTABLE_MARKER = "libqemu-system-aarch64.so"
    }
}

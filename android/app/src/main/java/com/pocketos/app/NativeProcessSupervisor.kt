package com.pocketos.app

import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    ): NativeProcessHandle = startProcess(command, workingDirectory, {}, onSerialOutput, onDiagnosticOutput)

    /** Registers the child immediately after ProcessBuilder returns, before output pumps are started. */
    @Synchronized
    fun startSupervised(
        command: List<String>,
        workingDirectory: File? = null,
        onProcessStarted: (NativeProcessHandle) -> Unit,
        onSerialOutput: (ByteArray) -> Unit = {},
        onDiagnosticOutput: (ByteArray) -> Unit = {},
    ): NativeProcessHandle = startProcess(command, workingDirectory, onProcessStarted, onSerialOutput, onDiagnosticOutput)

    private fun startProcess(
        command: List<String>,
        workingDirectory: File?,
        onProcessStarted: (NativeProcessHandle) -> Unit,
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
        val pumpState = ProcessPumpState()
        val pumps = CountDownLatch(2)
        val handle = NativeProcessHandle(process, pumpState, pumps, command.first())
        try {
            onProcessStarted(handle)
        } catch (failure: Throwable) {
            runCatching { handle.forceStop() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            pumps.countDown()
            pumps.countDown()
            throw failure
        }
        pump(process, process.inputStream, "pocketos-serial", "stdout", pumpState, pumps, onSerialOutput, onDiagnosticOutput)
        pump(process, process.errorStream, "pocketos-qemu-log", "stderr", pumpState, pumps, onDiagnosticOutput, onDiagnosticOutput)
        return handle
    }

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    private fun pump(
        process: Process,
        stream: InputStream,
        threadName: String,
        streamLabel: String,
        pumpState: ProcessPumpState,
        pumps: CountDownLatch,
        receiver: (ByteArray) -> Unit,
        onDiagnosticOutput: (ByteArray) -> Unit,
    ) {
        Thread({
            var forwardOutput = true
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = try {
                        stream.read(buffer)
                    } catch (failure: Throwable) {
                        if (process.isAlive && !pumpState.expectedClosure.get()) {
                            reportPumpFailure(onDiagnosticOutput, streamLabel, "read", failure)
                        }
                        break
                    }
                    if (count < 0) break
                    if (forwardOutput) {
                        try {
                            receiver(buffer.copyOf(count))
                        } catch (failure: Throwable) {
                            reportPumpFailure(onDiagnosticOutput, streamLabel, "consumer", failure)
                            // Keep draining the pipe so a chatty child cannot block on a full buffer.
                            forwardOutput = false
                        }
                    }
                }
            } finally {
                runCatching { stream.close() }
                pumps.countDown()
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    private fun reportPumpFailure(
        onDiagnosticOutput: (ByteArray) -> Unit,
        streamLabel: String,
        operation: String,
        failure: Throwable,
    ) {
        val safeMessage = "PocketOS $streamLabel pump $operation failed (${failure.javaClass.simpleName})\n"
        runCatching { onDiagnosticOutput(safeMessage.toByteArray(Charsets.UTF_8)) }
    }
}

internal class ProcessPumpState {
    val expectedClosure = AtomicBoolean(false)
}

class NativeProcessHandle internal constructor(
    private val process: Process,
    private val pumpState: ProcessPumpState,
    private val outputPumps: CountDownLatch,
    private val executable: String,
) : GuestProcess {
    private val previousThreadCpuTicks = mutableMapOf<String, Long>()

    /** Android's java.lang.Process stub has no pid() API; resolve our direct child from procfs. */
    val pid: Long
        get() = findChildPid() ?: -1L

    private fun findChildPid(): Long? = runCatching {
        val expectedName = File(executable).name
        val taskDirectories = File("/proc/self/task").listFiles().orEmpty()
        val childPids = taskDirectories.asSequence()
            .flatMap { task -> readProcFile(File(task, "children")).split(Regex("\\s+")).asSequence() }
            .mapNotNull(String::toLongOrNull)
            .distinct()
            .toList()
        childPids.firstOrNull { candidate ->
            val proc = File("/proc/$candidate")
            if (!proc.isDirectory) return@firstOrNull false
            val args = readProcFile(File(proc, "cmdline")).split('\\u0000')
            val processName = args.firstOrNull()?.let(::File)?.name.orEmpty()
            val state = readCpuStatState(File(proc, "stat"))
            processName == expectedName && state != "Z"
        } ?: childPids.firstOrNull { candidate ->
            val state = readCpuStatState(File("/proc/$candidate/stat"))
            state.isNotEmpty() && state != "Z"
        }
    }.getOrNull()

    private fun readCpuStatState(statFile: File): String = runCatching {
        statFile.readText().substringAfterLast(") ").trim().substringBefore(' ')
    }.getOrDefault("")

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
        expectOutputClosure()
        if (process.isAlive) {
            process.destroyForcibly()
        }
        return process.waitFor(timeout, unit)
    }

    override fun expectOutputClosure() {
        pumpState.expectedClosure.set(true)
    }

    internal fun awaitOutputPumps(timeout: Long, unit: TimeUnit): Boolean = outputPumps.await(timeout, unit)

    fun exitStatusLabel(): String = runCatching {
        if (process.isAlive) "running" else "exited(${process.exitValue()})"
    }.getOrDefault("unavailable")

    /** Best-effort, bounded snapshot of the exact child PID and its procfs threads. */
    @Synchronized
    fun diagnosticSnapshot(): String = runCatching {
        val pid = findChildPid()
        if (pid == null) {
            return "qemuProcfs=pid-unavailable alive=$isAlive status=${exitStatusLabel()}"
        }
        val processDir = File("/proc/$pid")
        if (!processDir.isDirectory) {
            "qemuProcfs=unavailable(pid=$pid alive=$isAlive)"
        } else {
            val status = readProcFile(File(processDir, "status"))
            val statusFields = listOf(
                "State", "VmRSS", "VmSwap", "Threads", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches",
            ).mapNotNull { field ->
                status.lineSequence().firstOrNull { it.startsWith("$field:") }?.trim()
            }
            val taskEntries = File(processDir, "task").listFiles()
                ?.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
                ?.take(MAX_DIAGNOSTIC_THREADS)
                .orEmpty()
            val tasks = taskEntries.joinToString(prefix = "[", postfix = "]") { task ->
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
                val previousTicks = if (cpuTicks >= 0) previousThreadCpuTicks.put(task.name, cpuTicks) else null
                val cpuValue = cpuTicks.takeIf { it >= 0 }?.toString() ?: "?"
                val cpuDelta = when {
                    cpuTicks < 0 -> "?"
                    previousTicks == null -> "first"
                    else -> (cpuTicks - previousTicks).coerceAtLeast(0).toString()
                }
                "${task.name}:$name:$state:wchan=$wchan:cpuTicks=$cpuValue:cpuDeltaTicks=$cpuDelta:vcsw=$switches"
            }
            val threadCount = status.lineSequence().firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim() ?: "?"
            "qemuProcfs=pid=${processDir.name} alive=$isAlive status=${statusFields.joinToString(",")}; " +
                "threadCount=$threadCount sampledThreads=${taskEntries.size} threads=$tasks"
        }
    }.getOrElse {
        "qemuProcfs=unavailable pid=${pid} alive=$isAlive reason=${it.javaClass.simpleName}"
    }

    private fun readCpuTicks(statFile: File): Long = runCatching {
        val fields = statFile.readText().substringAfterLast(") ").trim().split(Regex("\\s+"))
        // After the parenthesized comm, fields[0] is state (field 3), followed by ppid through stime.
        fields[11].toLong() + fields[12].toLong()
    }.getOrElse { -1L }

    private fun readProcFile(file: File): String = runCatching { file.readText().trim() }.getOrElse { "unavailable" }

    private companion object {
        // QEMU normally has only a handful of threads; the cap keeps procfs snapshots bounded.
        const val MAX_DIAGNOSTIC_THREADS = 64
    }
}

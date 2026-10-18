package com.pocketos.app

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Starts a native guest engine as a child process of the dedicated VM service.
 * QEMU serial uses stdin/stdout; stderr stays separate for diagnostics.
 */
class NativeProcessSupervisor {
    @Volatile
    private var activeProcess: Process? = null

    @Synchronized
    fun start(
        command: List<String>,
        workingDirectory: File? = null,
        onSerialOutput: (ByteArray) -> Unit = {},
        onDiagnosticOutput: (ByteArray) -> Unit = {},
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

class NativeProcessHandle internal constructor(private val process: Process) {
    val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitValue()

    @Synchronized
    fun writeSerial(bytes: ByteArray) {
        check(process.isAlive) { "Native VM process is not running" }
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    fun awaitExit(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0) { "Timeout cannot be negative" }
        return process.waitFor(timeout, unit)
    }

    /** Last-resort process termination; callers should request QEMU shutdown first. */
    fun forceStop(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        if (process.isAlive) {
            process.destroyForcibly()
        }
        return process.waitFor(timeout, unit)
    }
}

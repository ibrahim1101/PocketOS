package com.pocketos.app

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

interface GuestProcess {
    val isAlive: Boolean
    fun writeSerial(bytes: ByteArray)
    fun awaitExit(timeout: Long, unit: TimeUnit): Boolean
    fun expectOutputClosure() {}
    fun forceStop(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): Boolean
}

interface GuestProcessLauncher {
    fun start(
        command: List<String>,
        workingDirectory: File? = null,
        onSerialOutput: (ByteArray) -> Unit = {},
        onDiagnosticOutput: (ByteArray) -> Unit = {},
    ): GuestProcess
}

fun interface ScheduledSessionTask {
    fun cancel()
}

fun interface SessionTaskScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledSessionTask
}

data class PreparedGuest(
    val command: List<String>,
    val workingDirectory: File? = null,
    val diskLease: AutoCloseable? = null,
    val requestGracefulShutdown: () -> Unit = {},
)

enum class GuestStopReason {
    USER_REQUEST,
    BATTERY_CUTOFF,
    BOOT_TIMEOUT,
    TIME_LIMIT,
    UNEXPECTED_EXIT,
    TERMINAL_IO_FAILURE,
    START_FAILURE,
}

/**
 * Coordinates a prepared guest, its native process, readiness, and session safety policy.
 * A foreground service can own this controller; startup remains dependent on its runtime gate.
 */
class GuestSessionController(
    private val lifecycle: VmLifecycle,
    private val coordinator: GuestBootCoordinator,
    private val processLauncher: GuestProcessLauncher,
    private val scheduler: SessionTaskScheduler,
    private val batteryPercent: () -> Int,
    private val gracefulShutdownTimeoutMs: Long = DEFAULT_SHUTDOWN_TIMEOUT_MS,
    private val forceStopTimeoutMs: Long = DEFAULT_FORCE_STOP_TIMEOUT_MS,
    private val bootTimeoutMs: Long = DEFAULT_BOOT_TIMEOUT_MS,
    private val onDiagnosticOutput: (ByteArray) -> Unit = {},
    private val onStateChanged: (VmState) -> Unit = {},
    private val onSerialOutput: (ByteArray) -> Unit = {},
    private val serialWriteTimeoutMs: Long = DEFAULT_SERIAL_WRITE_TIMEOUT_MS,
) {
    private val lock = Any()
    private var process: GuestProcess? = null
    private var activeGuest: PreparedGuest? = null
    private var deadlineTask: ScheduledSessionTask? = null
    private var bootWatchdog: IndependentWatchdog? = null
    private var launching = false
    @Volatile private var activeGeneration: Any? = null
    private val pendingSerial = mutableListOf<ByteArray>()

    @Volatile
    var lastStopReason: GuestStopReason? = null
        private set

    val state: VmState
        get() = lifecycle.state

    init {
        require(gracefulShutdownTimeoutMs > 0)
        require(forceStopTimeoutMs > 0)
        require(bootTimeoutMs > 0)
        require(serialWriteTimeoutMs > 0)
    }

    /** Prepare the disk and command, then launch once. A low battery rejects before preparation. */
    fun start(prepare: () -> PreparedGuest): Boolean = synchronized(lock) {
        if (!lifecycle.canStart() || !coordinator.canStart(readBatteryPercent())) return false
        if (!coordinator.beginPreparation()) return false
        lastStopReason = null

        try {
            val prepared = prepare()
            require(prepared.command.isNotEmpty()) { "Guest command cannot be empty" }
            activeGuest = prepared
            if (!coordinator.canStart(readBatteryPercent())) {
                failStartLocked()
                return false
            }
            check(coordinator.onPreparationComplete()) { "Guest preparation transition failed" }
            check(coordinator.onProcessStarted()) { "Guest process transition failed" }

            launching = true
            val generation = Any()
            activeGeneration = generation
            val startedProcess = processLauncher.start(
                command = prepared.command,
                workingDirectory = prepared.workingDirectory,
                onSerialOutput = { bytes -> receiveSerialOutput(generation, bytes) },
                onDiagnosticOutput = { bytes ->
                    if (activeGeneration === generation) runCatching { onDiagnosticOutput(bytes) }
                },
            )
            process = startedProcess
            bootWatchdog = IndependentWatchdog(bootTimeoutMs) {
                stop(GuestStopReason.BOOT_TIMEOUT, onlyWhileBooting = true, expectedProcess = startedProcess)
            }
            launching = false

            val earlyOutput = pendingSerial.toList()
            pendingSerial.clear()
            earlyOutput.forEach(::processSerialOutputLocked)

            Thread({ monitorProcess(startedProcess) }, "pocketos-process-wait").apply {
                isDaemon = true
                start()
            }
            publishState()
            true
        } catch (_: Exception) {
            failStartLocked()
            false
        }
    }

    /** Sends terminal input only after the guest has reached a real ready prompt. */
    fun writeSerial(bytes: ByteArray): Boolean {
        val current = synchronized(lock) {
            process?.takeIf { lifecycle.state == VmState.RUNNING && it.isAlive }
        } ?: return false
        val input = bytes.copyOf()
        val write = FutureTask {
            val allowed = synchronized(lock) { process === current && lifecycle.state == VmState.RUNNING }
            if (!allowed) false else {
                current.writeSerial(input)
                true
            }
        }
        Thread(write, "pocketos-terminal-write").apply { isDaemon = true; start() }
        return try {
            write.get(serialWriteTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            write.cancel(true)
            // Keep the Android service worker available for battery/user/deadline events.
            // The identity check prevents a late failure from stopping a replacement guest.
            Thread({
                val accepted = runCatching { stop(GuestStopReason.TERMINAL_IO_FAILURE, expectedProcess = current) }.getOrDefault(false)
                runCatching { onDiagnosticOutput("Terminal I/O failed; matching guest stop accepted=$accepted.\n".toByteArray()) }
            }, "pocketos-terminal-failure")
                .apply { isDaemon = true; start() }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            false
        }
    }

    /** Called from the event-driven Android battery receiver; Wi-Fi changes are intentionally ignored. */
    fun onBatteryChanged(percent: Int): Boolean {
        if (percent > SessionPolicy.BATTERY_CUTOFF_PERCENT) return false
        return when (lifecycle.state) {
            VmState.PREPARING, VmState.STARTING, VmState.BOOTING, VmState.RUNNING ->
                stop(GuestStopReason.BATTERY_CUTOFF)
            else -> false
        }
    }

    fun stopByUser(): Boolean = stop(GuestStopReason.USER_REQUEST)

    private fun stop(reason: GuestStopReason, onlyWhileBooting: Boolean = false, expectedProcess: GuestProcess? = null): Boolean {
        val currentProcess: GuestProcess?
        val guest: PreparedGuest?
        synchronized(lock) {
            if (expectedProcess != null && process !== expectedProcess) return false
            if (onlyWhileBooting && lifecycle.state != VmState.BOOTING) return false
            when (lifecycle.state) {
                VmState.RUNNING -> {
                    if (!coordinator.requestShutdown()) return false
                    if (!lifecycle.transitionTo(VmState.STOPPING)) return false
                }
                VmState.PREPARING, VmState.STARTING, VmState.BOOTING -> {
                    if (!lifecycle.transitionTo(VmState.STOPPING)) return false
                }
                else -> return false
            }
            lastStopReason = reason
            if (reason == GuestStopReason.BOOT_TIMEOUT) {
                runCatching {
                    onDiagnosticOutput("Guest boot deadline exceeded before serial readiness; stopping QEMU.\n".toByteArray())
                }
            }
            publishState()
            bootWatchdog?.close()
            bootWatchdog = null
            deadlineTask?.cancel()
            deadlineTask = null
            currentProcess = process
            guest = activeGuest
            if (currentProcess == null) {
                coordinator.onProcessExited()
                releaseGuestLocked()
                return true
            }
        }

        val processToStop = currentProcess ?: return true
        processToStop.expectOutputClosure()
        val request = FutureTask { guest?.requestGracefulShutdown?.invoke() }
        Thread(request, "pocketos-graceful-request").apply { isDaemon = true; start() }
        var requestInterrupted = false
        try {
            request.get(gracefulShutdownTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            request.cancel(true)
            // Even a stuck shutdown callback cannot prevent process termination.
            if (failure is InterruptedException) {
                requestInterrupted = true
                Thread.interrupted()
            }
        }

        val stoppedGracefully = runCatching {
            processToStop.awaitExit(gracefulShutdownTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!stoppedGracefully) {
            runCatching { processToStop.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS) }
        }
        if (!processToStop.isAlive) finishProcess(processToStop)
        if (requestInterrupted) Thread.currentThread().interrupt()
        return true
    }

    private fun receiveSerialOutput(generation: Any, bytes: ByteArray) {
        if (bytes.isEmpty() || activeGeneration !== generation) return
        runCatching { onSerialOutput(bytes.copyOf()) }
        synchronized(lock) {
            if (activeGeneration !== generation) return
            if (launching && process == null) {
                pendingSerial += bytes.copyOf()
                return
            }
            processSerialOutputLocked(bytes)
        }
    }

    private fun processSerialOutputLocked(bytes: ByteArray) {
        when (coordinator.onSerialOutput(bytes, readBatteryPercent())) {
            GuestBootEvent.READY -> {
                bootWatchdog?.close()
                bootWatchdog = null
                val remaining = coordinator.remainingSessionMs()
                if (remaining == null) {
                    lastStopReason = GuestStopReason.START_FAILURE
                    process?.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS)
                    return
                }
                val readyProcess = process
                deadlineTask = try {
                    scheduler.schedule(remaining) {
                        if (readyProcess != null) stop(GuestStopReason.TIME_LIMIT, expectedProcess = readyProcess)
                    }
                } catch (_: Exception) {
                    lastStopReason = GuestStopReason.START_FAILURE
                    process?.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS)
                    null
                }
            }
            GuestBootEvent.LOW_BATTERY_REJECTED -> {
                lastStopReason = GuestStopReason.BATTERY_CUTOFF
                process?.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS)
            }
            GuestBootEvent.CHECKPOINT_FAILURE -> {
                lastStopReason = GuestStopReason.START_FAILURE
                process?.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS)
            }
            else -> Unit
        }
        publishState()
    }

    private fun monitorProcess(current: GuestProcess) {
        while (current.isAlive) {
            val exited = runCatching { current.awaitExit(1, TimeUnit.DAYS) }.getOrDefault(false)
            if (exited) break
            if (!current.isAlive) break
        }
        finishProcess(current)
    }

    private fun finishProcess(current: GuestProcess) {
        synchronized(lock) {
            if (process !== current) return
            bootWatchdog?.close()
            bootWatchdog = null
            deadlineTask?.cancel()
            deadlineTask = null

            if (lastStopReason == null) lastStopReason = GuestStopReason.UNEXPECTED_EXIT
            if (lifecycle.state == VmState.FAILED) {
                coordinator.clearSession()
            } else {
                coordinator.onProcessExited()
            }
            process = null
            activeGeneration = null
            releaseGuestLocked()
            publishState()
        }
    }

    private fun failStartLocked() {
        launching = false
        activeGeneration = null
        pendingSerial.clear()
        bootWatchdog?.close()
        bootWatchdog = null
        if (lifecycle.state != VmState.FAILED) lifecycle.transitionTo(VmState.FAILED)
        coordinator.clearSession()
        lastStopReason = GuestStopReason.START_FAILURE
        releaseGuestLocked()
        publishState()
    }

    private fun publishState() {
        runCatching { onStateChanged(lifecycle.state) }
    }

    private fun releaseGuestLocked() {
        runCatching { activeGuest?.diskLease?.close() }
        activeGuest = null
    }

    private fun readBatteryPercent(): Int =
        runCatching { batteryPercent() }.getOrDefault(-1)

    companion object {
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000L
        const val DEFAULT_FORCE_STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_BOOT_TIMEOUT_MS = 180_000L
        const val DEFAULT_SERIAL_WRITE_TIMEOUT_MS = 3_000L
    }
}


package com.pocketos.app

import java.io.File
import java.util.concurrent.TimeUnit

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
) {
    private val lock = Any()
    private var process: GuestProcess? = null
    private var activeGuest: PreparedGuest? = null
    private var deadlineTask: ScheduledSessionTask? = null
    private var bootWatchdog: IndependentWatchdog? = null
    private var launching = false
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
            val startedProcess = processLauncher.start(
                command = prepared.command,
                workingDirectory = prepared.workingDirectory,
                onSerialOutput = ::receiveSerialOutput,
                onDiagnosticOutput = { bytes -> runCatching { onDiagnosticOutput(bytes) } },
            )
            process = startedProcess
            bootWatchdog = IndependentWatchdog(bootTimeoutMs) {
                stop(GuestStopReason.BOOT_TIMEOUT, onlyWhileBooting = true)
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
    fun writeSerial(bytes: ByteArray): Boolean = synchronized(lock) {
        val current = process ?: return false
        if (lifecycle.state != VmState.RUNNING || !current.isAlive) return false
        runCatching { current.writeSerial(bytes) }.isSuccess
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

    private fun stop(reason: GuestStopReason, onlyWhileBooting: Boolean = false): Boolean {
        val currentProcess: GuestProcess?
        val guest: PreparedGuest?
        synchronized(lock) {
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
        try {
            guest?.requestGracefulShutdown?.invoke()
        } catch (_: Exception) {
            // Bounded wait and force-stop below are the recovery path.
        }

        val stoppedGracefully = runCatching {
            processToStop.awaitExit(gracefulShutdownTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!stoppedGracefully) {
            runCatching { processToStop.forceStop(forceStopTimeoutMs, TimeUnit.MILLISECONDS) }
        }
        if (!processToStop.isAlive) finishProcess(processToStop)
        return true
    }

    private fun receiveSerialOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching { onSerialOutput(bytes.copyOf()) }
        synchronized(lock) {
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
                deadlineTask = try {
                    scheduler.schedule(remaining) { stop(GuestStopReason.TIME_LIMIT) }
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
            releaseGuestLocked()
            publishState()
        }
    }

    private fun failStartLocked() {
        launching = false
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
    }
}

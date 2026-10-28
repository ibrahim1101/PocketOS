package com.pocketos.app

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A deadline callback on a daemon worker, independent of the thread being supervised. */
internal class IndependentWatchdog(
    timeoutMs: Long,
    private val onTimeout: () -> Unit,
) : AutoCloseable {
    private val fired = AtomicBoolean(false)
    private val future: ScheduledFuture<*>

    init {
        require(timeoutMs > 0) { "Watchdog timeout must be positive" }
        future = executor.schedule({
            if (fired.compareAndSet(false, true)) {
                // Never run owner cleanup on the timer thread. Diagnostic collection and
                // process teardown may block; one bad callback must not delay every other
                // deadline scheduled by this process.
                Thread({ runCatching(onTimeout) }, "pocketos-watchdog-callback").apply {
                    isDaemon = true
                    start()
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    val hasFired: Boolean get() = fired.get()

    override fun close() {
        future.cancel(false)
    }

    private companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor(ThreadFactory { task ->
            Thread(task, "pocketos-independent-watchdog").apply { isDaemon = true }
        })
    }
}

/** Periodic health work that runs independently from the supervised caller. */
internal class IndependentHeartbeat(
    intervalMs: Long,
    private val onHeartbeat: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val future: ScheduledFuture<*>

    init {
        require(intervalMs > 0) { "Heartbeat interval must be positive" }
        future = heartbeatExecutor.scheduleAtFixedRate({
            if (!closed.get() && running.compareAndSet(false, true)) {
                Thread({
                    try {
                        runCatching(onHeartbeat)
                    } finally {
                        running.set(false)
                    }
                }, "pocketos-health-heartbeat").apply {
                    isDaemon = true
                    start()
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        closed.set(true)
        future.cancel(false)
    }

    private companion object {
        private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(ThreadFactory { task ->
            Thread(task, "pocketos-heartbeat-timer").apply { isDaemon = true }
        })
    }
}

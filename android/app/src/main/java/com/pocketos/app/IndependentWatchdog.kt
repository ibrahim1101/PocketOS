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
            if (fired.compareAndSet(false, true)) runCatching(onTimeout)
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

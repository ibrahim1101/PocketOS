package com.pocketos.app

import android.content.Context
import android.os.PowerManager

/** Keeps guest CPU execution available while Android blanks the display or enters idle. */
class CpuExecutionLease(
    context: Context,
    tag: String,
    private val fallbackTimeoutMs: Long = DEFAULT_FALLBACK_TIMEOUT_MS,
) : AutoCloseable {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }

    init {
        require(tag.isNotBlank()) { "Wake lock tag cannot be blank" }
        require(fallbackTimeoutMs > 0) { "Wake lock fallback timeout must be positive" }
    }

    val isHeld: Boolean
        get() = wakeLock.isHeld

    @Synchronized
    fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire(fallbackTimeoutMs)
    }

    @Synchronized
    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun close() = release()

    private companion object {
        const val DEFAULT_FALLBACK_TIMEOUT_MS = 65L * 60L * 1_000L
    }
}

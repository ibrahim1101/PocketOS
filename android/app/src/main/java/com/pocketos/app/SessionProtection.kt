package com.pocketos.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock

/** Persisted monotonic checkpoint, valid across app process restarts on the same boot. */
data class SessionCheckpoint(val startedAtElapsedMs: Long, val deadlineElapsedMs: Long)

interface SessionCheckpointStore {
    fun load(): SessionCheckpoint?
    fun save(checkpoint: SessionCheckpoint)
    fun clear()
}

fun interface ElapsedRealtimeClock {
    fun nowMs(): Long
}

enum class SessionStopReason {
    BATTERY_CUTOFF,
    TIME_LIMIT,
    DEVICE_REBOOT,
}

/**
 * Session policy engine. Call startWhenRunning only after a real guest-ready signal.
 * The elapsed-realtime deadline survives service process recreation without wall-clock drift.
 */
class SessionProtection(
    private val store: SessionCheckpointStore,
    private val clock: ElapsedRealtimeClock = ElapsedRealtimeClock { SystemClock.elapsedRealtime() },
) {
    fun canStart(batteryPercent: Int): Boolean = SessionPolicy.canStart(batteryPercent)

    fun startWhenRunning(batteryPercent: Int): Boolean {
        if (!canStart(batteryPercent)) return false
        val startedAt = clock.nowMs()
        require(startedAt >= 0) { "Elapsed realtime cannot be negative" }
        val deadline = if (Long.MAX_VALUE - startedAt < SessionPolicy.MAX_SESSION_MS) {
            Long.MAX_VALUE
        } else {
            startedAt + SessionPolicy.MAX_SESSION_MS
        }
        store.save(SessionCheckpoint(startedAt, deadline))
        return true
    }

    fun stopReason(batteryPercent: Int): SessionStopReason? {
        if (batteryPercent <= SessionPolicy.BATTERY_CUTOFF_PERCENT) {
            return SessionStopReason.BATTERY_CUTOFF
        }
        val checkpoint = store.load() ?: return null
        val now = clock.nowMs()
        if (now < checkpoint.startedAtElapsedMs) return SessionStopReason.DEVICE_REBOOT
        if (now >= checkpoint.deadlineElapsedMs) return SessionStopReason.TIME_LIMIT
        return null
    }

    fun remainingMs(): Long? {
        val checkpoint = store.load() ?: return null
        val now = clock.nowMs()
        if (now < checkpoint.startedAtElapsedMs) return 0
        return (checkpoint.deadlineElapsedMs - now).coerceAtLeast(0)
    }

    fun clear() = store.clear()
}

/** SharedPreferences-backed checkpoint store; commit makes the deadline durable before use. */
class SharedPreferencesSessionCheckpointStore(
    private val preferences: SharedPreferences,
) : SessionCheckpointStore {
    override fun load(): SessionCheckpoint? {
        if (!preferences.contains(KEY_STARTED) || !preferences.contains(KEY_DEADLINE)) return null
        val started = preferences.getLong(KEY_STARTED, -1)
        val deadline = preferences.getLong(KEY_DEADLINE, -1)
        if (started < 0 || deadline < started) return null
        return SessionCheckpoint(started, deadline)
    }

    override fun save(checkpoint: SessionCheckpoint) {
        check(checkpoint.startedAtElapsedMs >= 0 && checkpoint.deadlineElapsedMs >= checkpoint.startedAtElapsedMs)
        check(
            preferences.edit()
                .putLong(KEY_STARTED, checkpoint.startedAtElapsedMs)
                .putLong(KEY_DEADLINE, checkpoint.deadlineElapsedMs)
                .commit()
        ) { "Could not persist PocketOS session deadline" }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY_STARTED).remove(KEY_DEADLINE).commit()) {
            "Could not clear PocketOS session deadline"
        }
    }

    companion object {
        private const val KEY_STARTED = "session_started_elapsed_ms"
        private const val KEY_DEADLINE = "session_deadline_elapsed_ms"
    }
}

/** Event-driven battery sampling. Registration returns Android's sticky current battery state. */
class BatteryLevelMonitor(
    context: Context,
    private val onBatteryPercent: (Int) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::publishBattery)
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        sticky?.let(::publishBattery)
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun publishBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) onBatteryPercent((level * 100 / scale).coerceIn(0, 100))
    }
}

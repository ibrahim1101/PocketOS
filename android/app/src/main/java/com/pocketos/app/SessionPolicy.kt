package com.pocketos.app

/** Default PocketOS session limits shared by startup checks and runtime enforcement. */
object SessionPolicy {
    const val MAX_SESSION_MS = 60L * 60L * 1000L
    const val BATTERY_CUTOFF_PERCENT = 20
    const val STOP_ON_WIFI_DISCONNECT = false

    fun canStart(batteryPercent: Int) = batteryPercent > BATTERY_CUTOFF_PERCENT

    fun shouldStop(startElapsedMs: Long, nowElapsedMs: Long, batteryPercent: Int): Boolean =
        batteryPercent <= BATTERY_CUTOFF_PERCENT ||
            (nowElapsedMs >= startElapsedMs && nowElapsedMs - startElapsedMs >= MAX_SESSION_MS)
}

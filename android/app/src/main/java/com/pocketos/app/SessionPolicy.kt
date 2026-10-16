package com.pocketos.app

/** Pure policy. Real VM lifecycle and battery sampling are not implemented yet. */
object SessionPolicy {
    const val MAX_SESSION_MS = 60L * 60L * 1000L
    const val BATTERY_CUTOFF_PERCENT = 20
    const val STOP_ON_WIFI_DISCONNECT = false
    fun canStart(batteryPercent: Int) = batteryPercent > BATTERY_CUTOFF_PERCENT
    fun shouldStop(startElapsedMs: Long, nowElapsedMs: Long, batteryPercent: Int): Boolean =
        batteryPercent <= BATTERY_CUTOFF_PERCENT ||
            (nowElapsedMs >= startElapsedMs && nowElapsedMs - startElapsedMs >= MAX_SESSION_MS)
}

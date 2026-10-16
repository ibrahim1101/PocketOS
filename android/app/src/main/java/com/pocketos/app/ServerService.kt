package com.pocketos.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Reserved for the real VM foreground-service lifecycle. No VM is started here. */
class ServerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fail closed until real engine, notifications, and shutdown handling exist.
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

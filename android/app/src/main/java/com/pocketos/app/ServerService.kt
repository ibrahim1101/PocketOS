package com.pocketos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File

/** Foreground owner for the Linux guest; launch remains fail-closed until ARM64 validation. */
class ServerService : Service() {
    private lateinit var lifecycle: VmLifecycle
    private lateinit var controller: GuestSessionController
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var batteryMonitor: BatteryLevelMonitor
    private var currentStatus = "Stopped"
    private lateinit var diagnosticEvents: DiagnosticsEventStore
    private val terminalInputReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val input = intent?.getStringExtra(EXTRA_TERMINAL_INPUT) ?: return
            worker.post { controller.writeSerial((input + "\n").toByteArray(Charsets.UTF_8)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lifecycle = VmLifecycle()
        diagnosticEvents = DiagnosticsEventStore(File(filesDir, "diagnostics/events.log"))
        workerThread = HandlerThread("pocketos-vm-service").apply { start() }
        worker = Handler(workerThread.looper)
        val checkpoint = SessionProtection(
            SharedPreferencesSessionCheckpointStore(getSharedPreferences("session", MODE_PRIVATE))
        )
        val coordinator = GuestBootCoordinator(lifecycle, GuestReadinessProbe(), checkpoint)
        controller = GuestSessionController(
            lifecycle = lifecycle,
            coordinator = coordinator,
            processLauncher = NativeProcessSupervisor(),
            scheduler = SessionTaskScheduler { delay, task ->
                val runnable = Runnable(task)
                worker.postDelayed(runnable, delay)
                ScheduledSessionTask { worker.removeCallbacks(runnable) }
            },
            batteryPercent = ::currentBatteryPercent,
            onDiagnosticOutput = { bytes -> Log.d(TAG, "QEMU diagnostic bytes=${bytes.size}") },
            onSerialOutput = { bytes ->
                sendBroadcast(
                    Intent(ACTION_TERMINAL_OUTPUT).setPackage(packageName)
                        .putExtra(EXTRA_TERMINAL_OUTPUT, String(bytes, Charsets.UTF_8))
                )
            },
            onStateChanged = { state ->
                diagnosticEvents.record(DiagnosticEventType.STATE_CHANGED, state, controller.lastStopReason)
                val reason = if (state == VmState.STOPPED || state == VmState.FAILED) controller.lastStopReason else null
                currentStatus = state.name.lowercase().replaceFirstChar { it.uppercase() } +
                    (reason?.let { " · $it" } ?: "")
                publishStatus(currentStatus)
                updateNotification(currentStatus)
            },
        )
        val inputFilter = android.content.IntentFilter(ACTION_TERMINAL_INPUT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(terminalInputReceiver, inputFilter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(terminalInputReceiver, inputFilter)
        }
        batteryMonitor = BatteryLevelMonitor(this) { percent -> worker.post { controller.onBatteryChanged(percent) } }
        batteryMonitor.start()
        publishStatus(currentStatus)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUERY -> {
                publishStatus(currentStatus)
                if (lifecycle.state == VmState.STOPPED || lifecycle.state == VmState.FAILED) stopSelf(startId)
            }
            ACTION_STOP -> worker.post {
                diagnosticEvents.record(DiagnosticEventType.STOP_REQUESTED, lifecycle.state)
                controller.stopByUser()
                val reason = controller.lastStopReason
                currentStatus = "Stopped" + (reason?.let { " · $it" } ?: "")
                publishStatus(currentStatus)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            ACTION_START, null -> {
                diagnosticEvents.record(DiagnosticEventType.START_REQUESTED, lifecycle.state)
                startForeground(NOTIFICATION_ID, buildNotification("Starting"))
                worker.post {
                    val result = AndroidRuntimeValidation.evaluate(this)
                    if (!result.allowed) {
                        diagnosticEvents.record(DiagnosticEventType.RUNTIME_GATE_BLOCKED, lifecycle.state)
                        currentStatus = "Blocked: ${result.reason}"
                        publishStatus(currentStatus)
                        updateNotification(currentStatus)
                        stopSelf(startId)
                        return@post
                    }
                    currentStatus = "Preparing"
                    publishStatus(currentStatus)
                    if (!controller.start(::prepareGuest)) {
                        currentStatus = controller.lastStopReason?.let { "Failed: $it" } ?: "Unable to start"
                        publishStatus(currentStatus)
                        updateNotification(currentStatus)
                        stopSelf(startId)
                    }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(terminalInputReceiver)
        batteryMonitor.close()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    private fun prepareGuest(): PreparedGuest {
        val assets = GuestAssetInstaller.forContext(this).install()
        val qmpSocket = File(filesDir, "pocketos-qmp.sock")
        qmpSocket.delete()
        val executable = File(applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        check(executable.isFile && executable.canExecute()) { "Packaged ARM64 QEMU executable is unavailable" }
        val settings = GuestRuntimeSettings.load(getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE))
        val hostBindAddress = if (settings.lanExposureEnabled) {
            WifiLanAddress.activeIpv4(this)
                ?: error("LAN exposure requires an active Wi-Fi network with an IPv4 address")
        } else {
            "127.0.0.1"
        }
        val config = QemuLaunchConfig(
            executable, assets.kernel, assets.initramfs, assets.rootDisk, qmpSocket,
            memoryMiB = settings.memoryMiB,
            vcpus = settings.vcpus,
            hostHttpPort = settings.hostHttpPort,
            hostBindAddress = hostBindAddress,
        )
        val command = config.toCommand()
        val lease = GuestDiskLease.acquire(assets.rootDisk)
        return PreparedGuest(
            command = command,
            workingDirectory = filesDir,
            diskLease = lease,
            requestGracefulShutdown = { QmpClient().requestQuit(qmpSocket.absolutePath) },
        )
    }

    private fun currentBatteryPercent(): Int {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun publishStatus(value: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, value))
        ServerWidget.refreshStatus(this, value)
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PocketOS")
            .setContentText(status)
            .setOngoing(status !in listOf("Stopped") && !status.startsWith("Blocked") && !status.startsWith("Failed"))
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PocketOS VM", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_QUERY = "com.pocketos.app.QUERY_VM"
        const val ACTION_START = "com.pocketos.app.START_VM"
        const val ACTION_STOP = "com.pocketos.app.STOP_VM"
        const val ACTION_STATUS = "com.pocketos.app.VM_STATUS"
        const val ACTION_TERMINAL_INPUT = "com.pocketos.app.TERMINAL_INPUT"
        const val ACTION_TERMINAL_OUTPUT = "com.pocketos.app.TERMINAL_OUTPUT"
        const val EXTRA_STATUS = "status"
        const val EXTRA_TERMINAL_INPUT = "terminal_input"
        const val EXTRA_TERMINAL_OUTPUT = "terminal_output"
        private const val SETTINGS_PREFS = "guest_settings"
        private const val TAG = "PocketOS"
        private const val CHANNEL_ID = "pocketos-vm"
        private const val NOTIFICATION_ID = 4101

    }
}

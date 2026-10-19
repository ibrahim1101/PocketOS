package com.pocketos.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var terminalInput: EditText
    private lateinit var terminalSend: Button
    private lateinit var terminalOutput: TextView
    private val terminalBuffer = StringBuilder()
    private var vmStatus = "Blocked until ARM64 runtime validation passes"

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_STATUS -> {
                    vmStatus = intent.getStringExtra(ServerService.EXTRA_STATUS) ?: "Unknown"
                    statusLabel.text = "VM status: $vmStatus"
                    renderButtons()
                }
                ServerService.ACTION_TERMINAL_OUTPUT ->
                    appendTerminal(intent.getStringExtra(ServerService.EXTRA_TERMINAL_OUTPUT).orEmpty())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 16)
            setBackgroundColor(Color.rgb(15, 20, 27))
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        column.addView(label("PocketOS", 30f))
        column.addView(label("Independent Alpine ARM64 Linux guest", 16f))
        statusLabel = label("VM status: $vmStatus", 16f)
        column.addView(statusLabel)
        startButton = Button(this).apply {
            text = "Start PocketOS"
            setOnClickListener {
                requestNotificationsIfNeeded()
                val start = Intent(this@MainActivity, ServerService::class.java).setAction(ServerService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
            }
        }
        stopButton = Button(this).apply {
            text = "Stop PocketOS"
            setOnClickListener {
                startService(Intent(this@MainActivity, ServerService::class.java).setAction(ServerService.ACTION_STOP))
            }
        }
        column.addView(startButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(stopButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow.addView(Button(this).apply { text = "Settings"; setOnClickListener { showSettings() } })
        toolsRow.addView(Button(this).apply { text = "Storage"; setOnClickListener { showStorage() } })
        toolsRow.addView(Button(this).apply { text = "Diagnostics"; setOnClickListener { showDiagnostics() } })
        column.addView(toolsRow)
        column.addView(label(
            "Startup requires ARM64 Android QEMU execution and guest boot validation. Session limit: 60 minutes after readiness; battery cutoff: 20%; Wi-Fi loss does not stop the guest.",
            13f,
        ))
        column.addView(label("Guest terminal", 18f))
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(7, 10, 14)) }
        terminalOutput = TextView(this).apply {
            setTextColor(Color.rgb(190, 235, 200))
            textSize = 13f
            setPadding(12, 12, 12, 12)
            text = "Guest serial output appears here. Terminal contents are not saved.\n"
        }
        scroll.addView(terminalOutput)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val terminalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        terminalInput = EditText(this).apply {
            hint = "Guest input"
            isSingleLine = true
            isEnabled = false
            setOnEditorActionListener { _, _, _ -> sendTerminalInput(); true }
        }
        terminalSend = Button(this).apply {
            text = "Send"
            isEnabled = false
            setOnClickListener { sendTerminalInput() }
        }
        terminalRow.addView(terminalInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        terminalRow.addView(terminalSend)
        column.addView(terminalRow)
        setContentView(column)
        renderButtons()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_STATUS)
            addAction(ServerService.ACTION_TERMINAL_OUTPUT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        renderButtons()
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_QUERY))
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun renderButtons() {
        val gate = AndroidRuntimeValidation.evaluate(this)
        startButton.isEnabled = gate.allowed
        if (!gate.allowed) {
            vmStatus = "Blocked: ${gate.reason}"
            statusLabel.text = "VM status: $vmStatus"
        }
        val running = vmStatus.contains("Running", ignoreCase = true)
        stopButton.isEnabled = running
        terminalInput.isEnabled = running
        terminalSend.isEnabled = running
    }

    private fun showSettings() {
        val current = GuestRuntimeSettings.load(getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        fun field(label: String, value: Int): EditText {
            val input = EditText(this).apply {
                hint = label
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            form.addView(input)
            return input
        }
        val memory = field("Guest memory MiB (512–4096)", current.memoryMiB)
        val cpus = field("Guest vCPUs (1–4)", current.vcpus)
        val port = field("Host HTTP port (1024–65535)", current.hostHttpPort)
        val lanExposure = CheckBox(this).apply {
            text = "Expose HTTP to devices on active Wi-Fi"
            isChecked = current.lanExposureEnabled
        }
        form.addView(lanExposure)
        AlertDialog.Builder(this).setTitle("Guest settings").setView(form)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    GuestRuntimeSettings(
                        memoryMiB = memory.text.toString().toInt(),
                        vcpus = cpus.text.toString().toInt(),
                        hostHttpPort = port.text.toString().toInt(),
                        lanExposureEnabled = lanExposure.isChecked,
                    )
                }.onSuccess { settings ->
                    if (settings.lanExposureEnabled) {
                        AlertDialog.Builder(this)
                            .setTitle("Expose HTTP on Wi-Fi?")
                            .setMessage(
                                "Other reachable devices on the active Wi-Fi network may connect to host port " +
                                    settings.hostHttpPort + " and reach guest port 8080. Use guest services " +
                                    "with their own authentication. PocketOS binds only to the active Wi-Fi IPv4 address."
                            )
                            .setPositiveButton("Enable LAN exposure") { _, _ -> saveSettings(settings) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        saveSettings(settings)
                    }
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Invalid settings", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveSettings(settings: GuestRuntimeSettings) {
        runCatching { settings.save(getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)) }
            .onSuccess {
                Toast.makeText(this, "Settings saved for the next guest start", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Toast.makeText(this, it.message ?: "Could not save settings", Toast.LENGTH_LONG).show()
            }
    }

    private fun storageStatus(): GuestStorageStatus =
        GuestStorageStatus.read(java.io.File(filesDir, "guest/rootfs.ext4"), filesDir)

    private fun showStorage() {
        val storage = storageStatus()
        val details = buildList {
            add("Persistent guest disk capacity: " + storage.diskCapacityLabel)
            add("Device free space: " + storage.availableDeviceLabel)
            add("Guest filesystem free space: " + storage.guestFreeSpaceLabel)
            if (storage.lowDeviceSpace) {
                add("Low device storage may prevent guest writes or future asset installation.")
            }
            add("The disk stays in private app storage across guest restarts.")
            add("Device free space is not the guest filesystem's free space.")
            add("Export creates a byte-for-byte ext4 backup while the guest disk is stopped.")
            add("Disk resizing and import are not enabled yet.")
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("Storage").setMessage(details)
            .setPositiveButton("Export disk") { _, _ -> requestDiskExport() }
            .setNegativeButton("Close", null).show()
    }

    private fun requestDiskExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "pocketos-rootfs.ext4")
        }
        startActivityForResult(intent, REQUEST_DISK_EXPORT)
    }

    @Deprecated("Use the Activity Result API when the app's activity dependencies are migrated.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DISK_EXPORT || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        val disk = java.io.File(filesDir, "guest/rootfs.ext4")
        Toast.makeText(this, "Exporting guest disk…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                GuestDiskExporter.export(disk) {
                    contentResolver.openOutputStream(destination, "w")
                        ?: error("Could not open the selected export destination")
                }
            }.onSuccess { bytes ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Guest disk exported (" + GuestStorageStatus.formatBytes(bytes) + ")",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message ?: "Guest disk export failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun showDiagnostics() {
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val storage = storageStatus()
        val gate = AndroidRuntimeValidation.evaluate(this)
        val details = listOf(
            "VM status: $vmStatus",
            "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
            "Android API: ${Build.VERSION.SDK_INT}",
            "Runtime gate: ${gate.reason}",
            "QEMU execution evidence: ${gate.qemuExecutionVerified}",
            "Guest boot evidence: ${gate.guestBootVerified}",
            "Available host memory: ${memoryInfo.availMem / (1024 * 1024)} MiB",
            "Persistent guest disk capacity: " + storage.diskCapacityLabel,
            "Device free space: " + storage.availableDeviceLabel,
            "Guest filesystem free space: " + storage.guestFreeSpaceLabel,
            "Network exposure: " + if (GuestRuntimeSettings.load(getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)).lanExposureEnabled) "Wi-Fi LAN (active IPv4 only)" else "Loopback only",
        ).joinToString("\n")
        AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(details).setPositiveButton("OK", null).show()
    }

    private fun appendTerminal(text: String) {
        if (text.isEmpty()) return
        terminalBuffer.append(text)
        if (terminalBuffer.length > MAX_TERMINAL_CHARS) {
            terminalBuffer.delete(0, terminalBuffer.length - MAX_TERMINAL_CHARS)
        }
        terminalOutput.text = terminalBuffer.toString()
        if (text.contains("password", ignoreCase = true)) {
            terminalInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else if (text.contains("login", ignoreCase = true) || text.contains("$", ignoreCase = true) || text.contains("#", ignoreCase = true)) {
            terminalInput.inputType = InputType.TYPE_CLASS_TEXT
        }
        terminalOutput.post { (terminalOutput.parent as? ScrollView)?.fullScroll(ViewGroup.FOCUS_DOWN) }
    }

    private fun sendTerminalInput() {
        val input = terminalInput.text?.toString().orEmpty()
        if (input.isEmpty()) return
        sendBroadcast(
            Intent(ServerService.ACTION_TERMINAL_INPUT).setPackage(packageName)
                .putExtra(ServerService.EXTRA_TERMINAL_INPUT, input)
        )
        terminalInput.text?.clear()
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 42
        private const val REQUEST_DISK_EXPORT = 43
        private const val SETTINGS_PREFS = "guest_settings"
        private const val MAX_TERMINAL_CHARS = 32_768
    }
}

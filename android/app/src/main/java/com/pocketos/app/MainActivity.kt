package com.pocketos.app

import android.Manifest
import android.app.Activity
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
            singleLine = true
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
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun renderButtons() {
        val gate = AndroidRuntimeValidation.evaluate(
            Build.SUPPORTED_ABIS.toList(),
            ServerService.QEMU_EXECUTION_VERIFIED_ON_ANDROID,
            ServerService.GUEST_BOOT_VERIFIED_ON_ANDROID,
        )
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
        private const val MAX_TERMINAL_CHARS = 32_768
    }
}

package com.pocketos.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            statusLabel.text = "VM status: ${intent?.getStringExtra(ServerService.EXTRA_STATUS) ?: "Unknown"}"
            renderButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 56, 36, 24)
            setBackgroundColor(Color.rgb(15, 20, 27))
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(0, 0, 0, 22)
        }
        column.addView(label("PocketOS", 30f))
        column.addView(label("Independent Alpine ARM64 Linux guest", 16f))
        statusLabel = label("VM status: Runtime validation required", 16f)
        column.addView(statusLabel)
        startButton = Button(this).apply {
            text = "Start PocketOS"
            setOnClickListener { requestNotificationsIfNeeded(); startVmService() }
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
            "Startup is gated until QEMU execution and Alpine guest boot pass on ARM64 Android.\n" +
                "Session limit: 60 minutes after guest readiness · Battery cutoff: 20% · Wi-Fi loss does not stop the guest.",
            13f,
        ))
        setContentView(column)
        renderButtons()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ServerService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
        renderButtons()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun renderButtons() {
        val blocked = !AndroidRuntimeValidation.evaluate(
            Build.SUPPORTED_ABIS.toList(),
            ServerService.QEMU_EXECUTION_VERIFIED_ON_ANDROID,
            ServerService.GUEST_BOOT_VERIFIED_ON_ANDROID,
        ).allowed
        startButton.isEnabled = !blocked
        stopButton.isEnabled = statusLabel.text.toString().contains("RUNNING", true) ||
            statusLabel.text.toString().contains("BOOTING", true)
        if (blocked) statusLabel.text = "VM status: Blocked until ARM64 runtime validation passes"
    }

    private fun startVmService() {
        val intent = Intent(this, ServerService::class.java).setAction(ServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }
}

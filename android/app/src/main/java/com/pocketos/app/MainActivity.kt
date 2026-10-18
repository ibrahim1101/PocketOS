package com.pocketos.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 56, 36, 24)
            setBackgroundColor(Color.rgb(15, 20, 27))
        }
        fun label(text: String, size: Float): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 28)
        }
        column.addView(label("PocketOS", 30f))
        column.addView(label("Rootless CLI Linux server · Development scaffold", 16f))
        column.addView(label("VM status: NOT IMPLEMENTED\nNative bridge: ${NativeVmBridge.status()}\nQEMU engine: NOT LINKED\nGuest: Alpine ARM64 (host boot verified)\nSession cap: 60 minutes\nBattery cutoff: 20%\nWi-Fi disconnect shutdown: off", 15f))
        column.addView(Button(this).apply {
            text = "View implementation status"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Engine not integrated")
                    .setMessage("The QEMU binary, Alpine disk, serial terminal, and lifecycle controller must be integrated before server startup is enabled.")
                    .setPositiveButton("OK", null).show()
            }
        })
        setContentView(column)
    }
}

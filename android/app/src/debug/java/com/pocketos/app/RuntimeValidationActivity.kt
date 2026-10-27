package com.pocketos.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Visible owner for debug instrumentation; does not launch a VM or bypass its gate. */
class RuntimeValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "PocketOS device validation is running. Keep this activity visible."
            setPadding(32, 64, 32, 32)
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    companion object {
        @Volatile var resumed = false
            private set
    }
}
